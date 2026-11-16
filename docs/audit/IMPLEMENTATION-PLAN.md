# Implementation Plan (agreed baseline for Stages 2–9)

This is the **single agreed plan** that later agents follow. It merges the four audits in `docs/audit/` (01 repository analysis, 02a functional, 02b OOM, 02c UML), settles the points where they disagree, and assigns every change to a stage, a prompt and an owner.

Paths are relative to `src/main/java/com/example/warehouseManagement/` unless they start with `src/` or `docs/`.

Baseline: commit `742d8da`, `mvn -o test` gives **32 tests, all passing**. Build command (offline only):
`~/.m2/wrapper/dists/apache-maven-3.9.3-bin/326f10f4/apache-maven-3.9.3/bin/mvn -o test` (never `clean`; delete `target/` by hand if needed).

---

## 0. Conflicts between the audits and how they are resolved

| # | Topic | 01 / 02a / 02c say | 02b says | **Decision** | Why (spot-checked in source) |
|---|---|---|---|---|---|
| C1 | Shipment entity | 01 and 02a: shipment tracking is missing and is core to the brief | "Not recommended. Picking job + invoice already represent shipment." | **Build a real `Shipment` module (Prompt 5) with Flyway `V3`.** | The brief lists shipment tracking as a required function. No carrier, tracking number, dispatch or delivery data exists anywhere (grep confirms). Status transitions on `SalesOrder` alone cannot record a carrier or a delivery. |
| C2 | Universal `BaseEntity` | 01 suggests `BaseEntity` for every entity | Not justified (builder churn, nothing new shown) | **No universal `BaseEntity`.** `@Id` stays in each entity. | It only hoists `id`. It would force `@SuperBuilder` on 22 entities and adds sequence-naming risk for no OOM gain. |
| C3 | Vendor / Customer | 01: `Party` `@MappedSuperclass` | `@Embeddable Address` (composition) | **`Party` `@MappedSuperclass`** (Prompts 6–8). Composition is shown elsewhere (order→lines, shipment→tracking events). | V1 shows identical columns (`name, street, city, state, zipcode, contact_info`) on `vendor` and `customer`. Six templates bind `*{street}` etc. and `CustomerServiceImpl`/`VendorServiceImpl` use `root.get("city")`. An `@Embeddable` would change all of those property paths. Inherited `@MappedSuperclass` fields keep every path, so there is no template, Specification or schema change. "Vendor is-a trading Party" is a valid generalisation, and `formattedAddress()` is shared behaviour. |
| C4 | Where order status is decided | 02a F11: picking fulfilment should set the SO to `PARTIALLY_SHIPPED` / `SHIPPED` | 02b: `SalesOrder.registerShipment(...)` called from fulfilment | **Shipments drive `SoStatus`**, not picking. Picking (Prompt 4) creates backorders. Shipment dispatch (Prompt 5) calls `SalesOrder.markPartiallyShipped()` / `markShipped()`, which Prompt 4 adds. | Once a Shipment module exists, "shipped" has to mean dispatched to a carrier. Putting both rules in place would make two writers for one status. |
| C5 | Partial receipt design | 02a: compare quantities and either create a new PENDING GRN or allow another receipt | 02b: `PurchaseOrder.registerReceipt(boolean)` | Both are combined. **Quantity-based outstanding per item.** The current GRN becomes FULFILLED. If anything is still outstanding, the PO becomes `PARTIALLY_RECEIVED` and one new PENDING GRN is opened (find-or-create). The status rule lives in `PurchaseOrder.registerReceipt(boolean fullyReceived)`. | This matches the intent of the commented-out `GoodsReceiptNoteImplBack` and the existing tables. No migration. |
| C6 | `GoodsReceiptNoteRepository.findByPurchaseOrder` mapping | 02a/02c: alias `"purchaseOrder"` may break entity mapping (Plausible) | — | **Replace it** with derived queries no matter what. The plausible bug does not need proving first. | Confirmed in source: the native query aliases `purchase_order_id AS "purchaseOrder"` and returns `Optional`, which throws once a PO has 2+ GRNs. |
| C7 | Exception style | Existing `*NotFoundException` are **checked** (`extends Exception`) | 02b: checked `WarehouseException` base | **Two families.** (a) Existing checked not-found and modification exceptions get checked parents in Prompts 6–8. (b) **New business-rule failures are unchecked** under `BusinessRuleException extends RuntimeException`, created in Prompt 3. | Domain methods on entities (`markShipped()`, `Stock.decrease()`) cannot reasonably declare checked exceptions. Every existing `throws` clause stays valid. |
| C8 | Enum persistence | All: the four status enums are ORDINAL, and native SQL hard-codes the ordinals | — | **Never reorder or insert constants in `PoStatus`, `SoStatus`, `GrnStatus` or `PjStatus`.** New enums (`ShipmentStatus`, `ShippingMethod`) use `@Enumerated(EnumType.STRING)` with `varchar` + `check`, the same as V2's `stock_adjustment.type`. | V2 proves that `@Enumerated(STRING)` with `varchar` passes `ddl-auto=validate`. |
| C9 | Unique (section, item) constraint on `stock` | 02a F8: optional, needs a migration | — | **No constraint. Merge-on-insert in code** (Prompt 3). | It would need data cleanup plus a migration number, and that number would collide with the reservations in §2.6. |
| C10 | `@Version` optimistic locking (F18) | 02a: optional, needs a migration | — | **Not implemented. Documented as a known limitation** (README, Stage 8). | Status guards (F9) stop the sequential double-submit. Real concurrency is out of scope for a single-user demo. |
| C11 | Cascade on `Vendor.items`, `Item.*` lines | 01 G-13, 02b, 02c: cascade ALL contradicts aggregation/association | — | **Reduce cascades in Prompts 6–8** (table in §3.6), and add delete guards so FK violations surface as `?failedToDelete`. | `Bootstrap` saves every child through its own repository (lines 163–381), so seeding does not rely on these cascades. |

---

## 1. Confirmed bugs to fix in Stage 2

Stage 2 runs **sequentially before** the parallel Stage 3/4 agents. **Stage 2 adds no Flyway migration** (none is needed; see C9 and C10).

### 1.0 Shared groundwork, created by Prompt 3 and reused by everyone

New files in `Domains/Exceptions/`:

| Class | Parent | Used for |
|---|---|---|
| `BusinessRuleException` (abstract) | `RuntimeException` | base of every new business-rule failure |
| `InvalidStatusTransitionException` | `BusinessRuleException` | illegal lifecycle change on any entity (PO, GRN, picking job, SO, Shipment) |
| `InsufficientStockException` | `BusinessRuleException` | stock would go below zero / bin total too small |
| `InvalidReceiptException` | `BusinessRuleException` | negative qty, over-receipt, DTO size mismatch, missing floor section |
| `InvalidStockAdjustmentException` | `BusinessRuleException` | bad qty, missing or identical destination |
| `GoodsReceiptNoteNotFoundException` | `Exception` (existing `*NotFound` convention) | missing GRN |

Prompt 4 adds `PickingJobNotFoundException` (checked), `PickingJobAlreadyFulfilledException` and `InvalidPickException` (both `BusinessRuleException`).

Every new exception gets a handler in `Controllers/GlobalExceptionHandler.java`, following the existing `redirect:/<list>?<flag>` convention. Controllers that need a form-specific redirect catch `BusinessRuleException` locally, as `StockAdjustmentController` does today.

Constants: add `Domains/WarehouseSection.FLOOR_SECTION = "00-00-0-0"` and `DAMAGES_SECTION = "11-11-1-1"` as `public static final` fields. Replace the magic strings in `GoodsReceiptNoteImpl`, `PutAwayController` and `Bootstrap`.

Domain methods added in Stage 2 (Prompts 6–8 later **lock down the setters but must keep these signatures**):

| Entity | Methods (exact names) | Prompt |
|---|---|---|
| `Stock` | `void increase(int qty)` (qty > 0, else `IllegalArgumentException`); `void decrease(int qty)` (throws `InsufficientStockException`); `boolean isEmpty()` | 3 |
| `PurchaseOrder` | `void registerReceipt(boolean fullyReceived)` (IN_TRANSIT/PARTIALLY_RECEIVED → PARTIALLY_RECEIVED or RECEIVED; from RECEIVED → `InvalidStatusTransitionException`); `boolean isModifiable()` (status == IN_TRANSIT and no FULFILLED GRN); `boolean isReceivable()` (status != RECEIVED) | 3 |
| `GoodsReceiptNote` | `void markFulfilled()` (PENDING → FULFILLED, else `InvalidStatusTransitionException`); `boolean isPending()` | 3 |
| `PickingJob` | `void markFulfilled()`; `boolean isPending()` | 4 |
| `PickingJobLine` | `int recordPick(WarehouseSection section, int qtyPicked)` returns the remainder `qtyToPick - qtyPicked` and enforces `0 <= qtyPicked <= qtyToPick` (`InvalidPickException`) | 4 |
| `Backorder` | `void changeQty(int newQty)` (≥ 0) | 4 |
| `SalesOrder` | `void markPartiallyShipped()` (PENDING/PARTIALLY_SHIPPED → PARTIALLY_SHIPPED; SHIPPED → `InvalidStatusTransitionException`); `void markShipped()` (PENDING/PARTIALLY_SHIPPED → SHIPPED; SHIPPED → exception); `boolean isModifiable()` (status == PENDING and no FULFILLED picking job); `boolean hasFulfilledPickingJob()` | 4 |

In Stage 2 these methods may still use the Lombok setter internally. Services must call the methods, never `setStatus`. The only remaining `setStatus` callers in `src/main` after Stage 2 should be inside these entity methods.

### 1.1 Prompt 3: inventory, supplier, purchase orders and GRN

| Ref | File / method | Fix |
|---|---|---|
| F1 / G-2 / D1 | `Services/GoodsReceiptNoteImpl.fulfill(GoodsReceiptNote, GoodsReceiptNoteDto)` lines 104–138 | Compute per item: ordered = Σ PO line qty; received = Σ qty on FULFILLED GRNs + this receipt. Call `purchaseOrder.registerReceipt(allItemsComplete)`, then `goodsReceiptNote.markFulfilled()`. If anything is still outstanding, open the next PENDING GRN through find-or-create (§F3) with qty-0 lines for the outstanding items only. Fix the missing `else`. |
| F2 / D7 | `Controllers/GoodsReceiptNoteController.finishReceivingShipment` lines 168–183 | Accept `IN_TRANSIT` **and** `PARTIALLY_RECEIVED` (use `po.isReceivable()`). All-zero receipt → `?nothingReceived` with nothing changed (the service signals this with a return value or `InvalidReceiptException`; it is never ignored). Missing GRN → `GoodsReceiptNoteNotFoundException` → `?notFound`. RECEIVED → `?alreadyReceived`. |
| F3 / G-3 / D2 | `PurchaseOrderServiceImpl.save` lines 212–235; `GoodsReceiptNoteController.startReceivingShipment` lines 139–166; `GoodsReceiptNoteImpl.create`; `Repositories/GoodsReceiptNoteRepository.findByPurchaseOrder` | One creation point: `GoodsReceiptNoteService.findOrCreatePending(PurchaseOrder)`. It returns the newest PENDING GRN or creates one with **qty-0** lines. `PurchaseOrderServiceImpl.save` delegates to it (this is also an SRP fix) instead of building the GRN itself, and the `getGoodsReceiptNotes().add(...)` call leaves the loop. The GET `/receive` calls find-or-create and is idempotent. Replace the native query with derived `Optional<GoodsReceiptNote> findFirstByPurchaseOrderIdAndStatusOrderByIdDesc(Long, GrnStatus)` and `List<GoodsReceiptNote> findByPurchaseOrderId(Long)`. Remove the old method. |
| F4 | `GoodsReceiptNoteImpl.fulfill` lines 112–127; `Domains/DTOs/GoodsReceiptNoteLineDto` | Add `Long goodsReceiptNoteLineId` to the DTO (hidden input in `goodsReceiptNotes/fulfillGoodsReceiptNoteForm.html`) and match rows by id. Validate qty ≥ 0, qty ≤ outstanding, and DTO size → `InvalidReceiptException`. Missing floor → `InvalidReceiptException` (no `.get()`). **Merge** into the existing floor `Stock` row (`stock.increase(qty)`) instead of inserting a new one. Keep notes on zero-qty lines. Add `@Min(0)` on DTO qty. |
| F8 / G-6 | `Services/StockServiceImpl.pickStock` lines 43–66; `Repositories/StockRepository.updateStockByWarehouseSectionAndItemId` / `deleteStockByWarehouseSectionAndItemId` (lines 372–396) | Add `StockService.decreaseAt(Long sectionId, Long itemId, int qty)` and `int availableAt(Long sectionId, Long itemId)` (bin **total**). Load every row for (section, item) as entities (new derived `findByWarehouseSectionIdAndItemId`) and consume them in order with `Stock.decrease`, deleting rows that reach 0. `pickStock(PickingJob)` uses `decreaseAt`. Delete both bulk `@Modifying` queries, with no remaining callers. |
| F15 | `StockServiceImpl.putAwayStocks` lines 96–126; `Controllers/PutAwayController` lines 30–51, 82–100; `Domains/DTOs/PutAwayTaskDto`; `templates/putAwayTasks/*` | Add `Long stockId` to `PutAwayTaskDto` (hidden input) and look rows up by id. Drop the `stocks` list parameter. Validate `1 ≤ qty ≤ qtyOnHand` (0 = skip), destination present and ≠ source → `InvalidStockAdjustmentException` → `?failed`. Merge moved qty into the destination row. |
| F16 / D6 | `Services/StockAdjustmentServiceImpl.apply` lines 18–84; `Controllers/StockAdjustmentController.save` lines 43–67 | Validate: CYCLE_COUNT qty ≥ 0, other types qty > 0; DAMAGE/TRANSFER need a destination ≠ source. Operate on the bin **total** (CYCLE_COUNT sets the total: merge rows, then set). Throw `InvalidStockAdjustmentException` / `InsufficientStockException` instead of `IllegalStateException`. The controller catches `BusinessRuleException` → `?failed`. **The `switch` stays** (Strategy belongs to Prompt 9). |
| F17 | `Repositories/StockRepository.findStockReportsItemsByVendorId` lines 208 and 218 | `po.status = 1` → `po.status IN (0, 2)`. Fix the comment at line 284 and the `SoStatus` comments in `Domains/SalesOrder.java` (`//1`, `//2`). |
| F13 (PO) / G-7 | `PurchaseOrderServiceImpl.updateById` lines 164–206 | Guard with `isModifiable()` → `ReceivedOrderModificationException`. Use `Objects.equals` for ids. When the item changes, refresh `itemCost` to the current cost. Size-mismatch guard (no IOOBE). Propagate line changes to the PENDING GRN (lines stay qty 0, so only item set and line count need syncing). Call `findById` once. |
| F14 (PO) / G-9 / G-14 | `Controllers/PurchaseOrderController.removePurchaseOrderLineFromUpdateOrderForm` lines 264–270; `deletePurchaseOrder` | Check the **persisted** order (`isModifiable()`), not the form-bound one. Return an existing view or redirect `?cannotBeUpdated` (the template `purchaseOrders/cannotBeUpdated` does not exist). Missing id → `PurchaseOrderNotFoundException`. Block deletion unless `isModifiable()` → `?failedToDelete`. |
| G-12 (PO) | `PurchaseOrderServiceImpl.save` (`findCurrentItemCostByItemId`) | A null current cost is rejected with a handled error (`?noCurrentCost` or a validation message), never stored as null. |
| G-10 | `Controllers/ItemController.deleteItem` | `redirect:/items/deleted` → `redirect:/items?deleted`. |
| G-11 | `PutAwayController.putAwayTaskOnFloor`, `GoodsReceiptNoteImpl.fulfill` | No bare `Optional.get()` (covered by F4 and F15). |
| Supplier | `Controllers/VendorController` create/update; `Domains/Vendor.java` | Add `@NotBlank` (name) and `@Size` limits on `Vendor` fields. Use `@Valid` + `BindingResult` in create/update and re-render the form with errors (the `AdminUserController` pattern). Missing vendor → `VendorNotFoundException` (already mapped). The existing "delete blocked when vendor has POs" rule stays. |
| F20 | `Services/GoodsReceiptNoteImplBack.java` | Delete the file (fully commented out). Git history keeps it. |
| Cleanup | `PurchaseOrderService.findByPurchaseOrderNumber` + `PurchaseOrderRepository` line 36 | Remove it (it queries a non-existent column and has no callers). |

### 1.2 Prompt 4: sales orders, picking and backorders

| Ref | File / method | Fix |
|---|---|---|
| F10 / G-4 / D5 (Facade) | `Controllers/PickingJobController.fulfillPickingJob` lines 147–167 | New `Services/OrderFulfilmentService` + `OrderFulfilmentServiceImpl`, with `@Transactional PickingJob fulfilPickingJob(Long pickingJobId, PickingJobDto dto)`. It orchestrates `PickingJobService`, `StockService`, `InvoiceService` and `BackorderService`. The controller only calls it and maps the outcome. Remove the unused `PurchaseOrderService` dependency from the controller. |
| F9 | `PickingJobServiceImpl.fulfill` line 152 / `PickingJobController.startPickingProcess` lines 109–136 | Guard `pickingJob.isPending()` → `PickingJobAlreadyFulfilledException` → `redirect:/picking-jobs?alreadyFulfilled`. The GET form redirects for non-PENDING jobs. Missing job → `PickingJobNotFoundException` → `?notFound`. |
| F5 / G-1 / D3 | `PickingJobServiceImpl.fulfill` lines 150–213 | No `remove(i)` inside the loop. Add `Long pickingJobLineId` to `PickingJobLineDto` (hidden input in `pickingJobs/fulfillPickingJobForm.html`) and pair by id. Validate DTO size → `InvalidPickException`. Unpicked lines **stay on the original job** with `qtyPicked = 0` (history, no orphans). Never call `setPickingJobLines(newList)`; mutate the existing list. This is a precondition for `orphanRemoval` in Prompts 6–8. |
| F6 / D8 | same, lines 171–181 | `picked = min(entered, availableAt(bin))` via `PickingJobLine.recordPick`, then `remainder = qtyToPick - picked`. Entered > qtyToPick → `InvalidPickException`. Each remainder > 0 becomes a line in **one** new PENDING backorder picking job (qtyToPick = remainder). |
| F7 | same, line 166; `StockServiceImpl.pickStock` line 57 | Negative qty → `InvalidPickException` (enforced in `recordPick`). Add `@Min(0)` on `PickingJobLineDto.qtyPicked`. |
| Nothing picked | same | If **no** line picks anything → `InvalidPickException("nothing picked")` → `?nothingPicked`. The job stays PENDING because it already represents the demand. This prevents chains of empty backorder jobs. |
| F11 (backorder part) | `PickingJobServiceImpl.fulfill` line 199; `Services/BackorderServiceImpl` | **Upsert per (salesOrder, item)**: after fulfilling a job, set that item's `Backorder.qty` to the total remainder for the item in this job, creating the row if absent and deleting it at 0 (`Backorder.changeQty`). Fulfilling a backorder job therefore resolves or reduces the existing row and never duplicates it. No schema change. |
| F11 (status part) | `SalesOrderServiceImpl.updateById` lines 153–155 | **Remove** the hidden `PARTIALLY_SHIPPED → SHIPPED` side effect. The SO status is now changed only by the Shipment module (C4) through `markPartiallyShipped()` / `markShipped()`, which this prompt adds and unit-tests. |
| F12 | `Services/WarehouseSectionServiceImpl.findBySalesOrder` lines 24–37; `PickingJobController` lines 91–92, 126–127; `templates/pickingJobs/fulfillPickingJobForm.html` lines 34–45 | Add `List<List<WarehouseSectionDto>> findByPickingJob(PickingJob)` that iterates the job's own lines, and use it in both controller methods. Apply the same fix to `findWarehouseSectionSuggestionBySalesOrder` on the details page. |
| F13 (SO) / G-7 | `SalesOrderServiceImpl.updateById` lines 143–193 | Guard with `isModifiable()` → `ShippedOrderModificationException` (→ `?cannotBeUpdated`). `Objects.equals`; when the item changes, refresh `itemPrice`; size-mismatch guard. **Propagate** line changes to the single PENDING picking job (qtyToPick/item updated; added lines create pj lines; removed lines delete pj lines). |
| F14 (SO) / G-8 / G-9 | `SalesOrderController.removeSaleOrderLineFromUpdateOrderForm` lines 268–274; `deleteSalesOrder` lines 319–330 | Check the persisted order's `isModifiable()`. Missing id → `SalesOrderNotFoundException` → `?notFound`, with no `Optional.get()` on an empty value. A non-existent template `salesOrders/cannotBeUpdated` becomes a redirect `?cannotBeUpdated`. Deletion is blocked unless `isModifiable()`. |
| G-12 (SO) | `SalesOrderServiceImpl.save` (`findCurrentItemPriceByItemId`) | A null current price → `ItemPriceNotFoundException` (exists, already mapped) or a form error. Never store a null price. |
| F19 | `Services/InvoiceServiceImpl.createByPickingJob` lines 40–53 | Create no invoice when no line has `qtyPicked > 0`. Replace `System.out.printf` with SLF4J. |

---

## 2. Shipment module design (Prompt 5, Stage 3)

### 2.1 Granularity: split shipments

**One shipment per fulfilled picking job** (`picking_job_id` is unique). An SO that needs backorders therefore gets several shipments over time, one per picking job. The shipment contents are the picking job's lines with `qtyPicked > 0`, so **no `ShipmentLine` table** is needed. The user creates a shipment by hand from a FULFILLED picking job that has picked qty > 0 and no shipment yet. Creation is **not** automatic inside picking, which keeps Prompt 4's code untouched.

### 2.2 Entities (all in `Domains/`, no `@Data`)

`ShipmentStatus` (top-level enum, `@Enumerated(STRING)`): `CREATED, DISPATCHED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, DELIVERY_FAILED, RETURNED, CANCELLED`. It has `Set<ShipmentStatus> allowedNext()`, `boolean canTransitionTo(ShipmentStatus)`, `boolean isTerminal()` and `boolean countsAsShipped()` (true for DISPATCHED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERY_FAILED, DELIVERED).

`ShippingMethod` (top-level enum, STRING): `STANDARD, EXPRESS, OVERNIGHT`. Prompt 9 hangs `ShippingStrategy` off it.

`Shipment` (table `shipment`), with `@Getter` and a protected no-arg constructor for JPA. Status and timestamps have no public setters.

| Field | Type | Column | Notes |
|---|---|---|---|
| id | Long, `@GeneratedValue(AUTO)` | `id` | `shipment_SEQ` |
| trackingNumber | String | `tracking_number varchar(32) not null unique` | generated `TRK-` + 10 random upper-alphanumerics; regenerated if it already exists |
| carrier | String | `carrier varchar(64) not null` | `@NotBlank` on the form DTO |
| shippingMethod | ShippingMethod | `shipping_method varchar(16) not null` | default STANDARD |
| shippingCost | Double (nullable) | `shipping_cost float(53)` | **reserved for Prompt 9** (ShippingStrategy). Prompt 5 leaves it null |
| estimatedDeliveryDate | LocalDate (nullable) | `estimated_delivery_date date` | entered on the form in Prompt 5; computed by strategy in Prompt 9 |
| status | ShipmentStatus | `status varchar(20) not null` | starts CREATED |
| salesOrder | `@ManyToOne` SalesOrder | `sales_order_id bigint not null` FK | **unidirectional**: `SalesOrder.java` gets no new field |
| pickingJob | `@ManyToOne` PickingJob | `picking_job_id bigint not null unique` FK | unidirectional |
| shipToAddress | String | `ship_to_address varchar(512)` | snapshot of `customer.formattedAddress()` at creation |
| createdAt / dispatchedAt / deliveredAt | LocalDateTime | `timestamp(6)` (`created_at not null`) | set by `transitionTo` |
| trackingEvents | `@OneToMany(mappedBy="shipment", cascade=ALL, orphanRemoval=true)` `@OrderBy("occurredAt ASC")` List | — | **composition**. Exposed read-only (`Collections.unmodifiableList`) |

Behaviour (encapsulation plus compile-time polymorphism by overloading):
- Constructor or static factory `Shipment.create(PickingJob job, String carrier, ShippingMethod method, LocalDate eta)`. It validates that the job is FULFILLED and picked something, then records the first `TrackingEvent(CREATED)`.
- `void transitionTo(ShipmentStatus next)` and the overload `void transitionTo(ShipmentStatus next, String location, String note)`. They throw `InvalidStatusTransitionException` when `!status.canTransitionTo(next)`, append a `TrackingEvent`, and stamp `dispatchedAt` / `deliveredAt`.
- `int getTotalUnits()` (Σ picked qty of the job lines).

`TrackingEvent` (table `tracking_event`): id (`tracking_event_SEQ`), `shipment` (`@ManyToOne`, `shipment_id not null`), `status varchar(20) not null`, `occurredAt timestamp(6) not null`, `location varchar(255)`, `note varchar(500)`. It is created only by `Shipment` (package-private or protected constructor). It is immutable.

### 2.3 `ShipmentStatus` transitions

| From \ To | DISPATCHED | IN_TRANSIT | OUT_FOR_DELIVERY | DELIVERED | DELIVERY_FAILED | RETURNED | CANCELLED |
|---|---|---|---|---|---|---|---|
| **CREATED** | ✔ | | | | | | ✔ |
| **DISPATCHED** | | ✔ | | | | | |
| **IN_TRANSIT** | | | ✔ | | | | |
| **OUT_FOR_DELIVERY** | | | | ✔ | ✔ | | |
| **DELIVERY_FAILED** | | | ✔ (retry) | | | ✔ | |
| **DELIVERED** | terminal | | | | | | |
| **RETURNED** | terminal | | | | | | |
| **CANCELLED** | terminal | | | | | | |

Any other pair, including X → X, throws `InvalidStatusTransitionException` → `redirect:/shipments/{id}?invalidTransition`.

### 2.4 SalesOrder integration (the existing `SoStatus` mechanism)

After every successful transition, `ShipmentServiceImpl` (one private method, `syncSalesOrderStatus(SalesOrder)`) recomputes:
- `shippedQty` = Σ picked qty of the SO's shipments whose status `countsAsShipped()`;
- `orderedQty` = Σ SO line qty;
- `shippedQty == 0` → no change (the SO stays PENDING);
- `shippedQty >= orderedQty` **and** the SO has no Backorder rows → `salesOrder.markShipped()`;
- otherwise → `salesOrder.markPartiallyShipped()` (no-op if it is already PARTIALLY_SHIPPED).

The SO status never moves backwards. RETURNED or CANCELLED after dispatch does not revert it (documented limitation). Prompt 9 may replace the direct call with an Observer event (§4).

**The Shipment agent must not edit `Domains/SalesOrder.java`.** It uses only this frozen API: `getId()`, `getCustomer()`, `getSaleOrderLines()`, `getBackorders()`, `getPickingJobs()`, `getStatus()`, `markPartiallyShipped()`, `markShipped()`. From `PickingJob` it uses `getId()`, `getStatus()`/`isPending()`, `getSalesOrder()`, `getPickingJobLines()`. From `PickingJobLine` it uses `getItem()` and `getQtyPicked()`. From `Customer` it uses `formattedAddress()`.

### 2.5 Service, repository, controller, UI

- `Repositories/ShipmentRepository extends JpaRepository<Shipment, Long>, JpaSpecificationExecutor<Shipment>`: `findByTrackingNumber`, `findBySalesOrderIdOrderByCreatedAtAsc`, `existsByPickingJobId`, `existsByTrackingNumber`, `countByStatus`. No `TrackingEventRepository` is needed (cascade from the root).
- `Services/ShipmentService` / `ShipmentServiceImpl`: overloads `createShipment(Long pickingJobId, String carrier, ShippingMethod method, LocalDate eta)` and `createShipment(Long pickingJobId, String carrier)` (defaults STANDARD, no ETA). Also `updateStatus(Long id, ShipmentStatus next, String location, String note)`, `findById`, `findByTrackingNumber`, `findAll(Pageable)`, `findBySalesOrder(Long)`, `findEligiblePickingJobs()`. All writes are `@Transactional`.
- Exceptions: `ShipmentNotFoundException` (checked, `*NotFound` convention). Not-eligible or duplicate shipment → `InvalidStatusTransitionException` or a new `ShipmentNotAllowedException extends BusinessRuleException`.
- `Controllers/ShipmentController` (`/shipments`): `GET /shipments` (list, filter by status and `salesOrderId`, pagination fragment); `GET /shipments/new?pickingJobId=`; `POST /shipments/new`; `GET /shipments/{id}`; `POST /shipments/{id}/status`; `GET /shipments/track?trackingNumber=`. Flags: `?created`, `?updated`, `?notFound`, `?invalidTransition`, `?notEligible`.
- `Controllers/ShipmentExceptionHandler`: `@ControllerAdvice(assignableTypes = ShipmentController.class)`, using the same redirect-flag convention. It is a separate file so that `GlobalExceptionHandler.java` stays owned by the OOM agent during the parallel stage.
- Form DTOs: `Domains/DTOs/NewShipmentDto` (`pickingJobId @NotNull`, `carrier @NotBlank @Size(max=64)`, `shippingMethod @NotNull`, `estimatedDeliveryDate @FutureOrPresent` optional), `Domains/DTOs/ShipmentStatusUpdateDto` (`status @NotNull`, `location @Size(max=255)`, `note @Size(max=500)`).
- Templates `templates/shipments/`: `shipments.html` (list + status filter), `newShipmentForm.html`, `shipmentDetails.html` (header, contents from picking-job lines, tracking timeline, and a status form that offers only `allowedNext()`), `track.html` (lookup by tracking number). Every form uses `th:action` (CSRF).
- Links (the only edits to existing templates): one nav entry in `templates/fragments/navbar.html`; a "Create shipment" / "View shipment" button in `templates/pickingJobs/pickingJobDetails.html`; a "Shipments" link to `/shipments?salesOrderId={id}` in `templates/salesOrders/salesOrderDetails.html`. **No controller other than `ShipmentController` is edited.**
- No Bootstrap seeding of shipments is required. If seeding is wanted, use a separate `Bootstrap/ShipmentSeeder` (`CommandLineRunner`, `@Order` after `Bootstrap`), and do not edit `Bootstrap.java`.

### 2.6 Flyway migration number reservations (binding)

| Version | Owner | Content |
|---|---|---|
| V1, V2 | existing | **never edit** |
| — | Stage 2 (Prompts 3, 4) | **no migration** |
| **V3** | **Shipment agent (Prompt 5)** | `V3__shipments.sql`: `shipment_SEQ`, `tracking_event_SEQ` (start 1, increment 50), tables `shipment` and `tracking_event`, `check` constraints on status and method values (same style as V2), FKs to `sales_order`, `picking_job` and `shipment`, unique on `tracking_number` and `picking_job_id` |
| **V4** | **OOM agent (Prompts 6–8)** | only if a schema change proves necessary. **Expected to be unused**: `@MappedSuperclass`, Lombok and cascade changes are all schema-neutral |
| V5+ | Prompt 9 and later, allocated sequentially by whichever stage runs next | e.g. none expected for strategies, because `shipping_cost` / `estimated_delivery_date` are already in V3 |

If V4 is unused, it stays a gap. Flyway allows gaps; a later stage must not "fill" V4 retroactively after V5 exists.

---

## 3. OOM changes (Prompts 6–8, Stage 4)

### 3.1 Entities that lose `@Data`

Replace `@Data` with `@Getter @Setter` on **every JPA entity** (all 22, plus the new `Party`/`AbstractOrder`). That removes the recursive `equals`/`hashCode`/`toString`. Then hide setters:

| Entity | `@Setter(AccessLevel.NONE)` on | Collection exposure |
|---|---|---|
| `PurchaseOrder`, `SalesOrder`, `GoodsReceiptNote`, `PickingJob` | `id`, `status` | no `set*Lines`; add `addLine(...)` / `removeLine(...)` that maintain the back-reference |
| `Stock` | `id`, `qtyOnHand` (mutate only via `increase`/`decrease`) | — |
| `Backorder` | `id`, `qty` (via `changeQty`) | — |
| `PurchaseOrderLine`, `SalesOrderLine`, `GoodsReceiptNoteLine`, `PickingJobLine`, `InvoiceLine` | `id` | — |
| `Vendor`, `Customer` (via `Party`) | `id` stays in the subclass with a setter (form binding uses hidden `id` on update forms; verify before hiding) | — |

Rules: keep `@NoArgsConstructor` (JPA). Keep the builder API (`X.builder()...build()`, including `.status(...)`, which `Bootstrap` uses) by switching to `@SuperBuilder` only on classes that extend a new superclass. Keep `@Builder.Default` on collections. Before relying on identity equality, grep for `.equals(`, `contains(`, `indexOf(`, `remove(Object)` on entities and for template `==` comparisons of entities. Compare ids where needed.

New and kept domain methods for Prompts 6–8, in addition to Stage 2's: `SalesOrder.addLine(SalesOrderLine)` / overload `addLine(Item item, int qty, ItemPrice price)`; `PurchaseOrder.addLine(PurchaseOrderLine)` / overload `addLine(Item item, int qty, ItemCost cost)`; `GoodsReceiptNote.addLine(...)`; `PickingJob.addLine(...)`; `Party.formattedAddress()` / overload `formattedAddress(String separator)`. Services and `Bootstrap` switch to `addLine` where they currently do `line.setX(this); list.add(line)`.

### 3.2 Inheritance (schema-neutral, no migration)

1. **`Domains/AbstractOrder<L extends OrderLine>`** (`@MappedSuperclass`, `@Getter @Setter @NoArgsConstructor @SuperBuilder`, abstract):
   - field `date` (`@Column(name="date")`, `@Builder.Default LocalDate.now()`), the only persistent field moved;
   - `public abstract List<L> getLines()` (PO returns `purchaseOrderLines`, SO returns `saleOrderLines`; **field names are not renamed**);
   - `public double getTotal()`, the Template Method over `getLines()` (removes both duplicate bodies);
   - `public abstract boolean isModifiable()` (implemented as in §1.0);
   - `public abstract String getStatusLabel()`.
   - `@Id` **stays in `PurchaseOrder` and `SalesOrder`**, so sequence resolution is untouched (`purchase_order_SEQ`, `sales_order_SEQ`).
   - **Justification:** the shared behaviour is real (total, modifiable rule, line contract). `@MappedSuperclass` gives inheritance and runtime polymorphism with zero tables or columns changed. JPA `SINGLE_TABLE`/`JOINED` would need a migration plus rewrites of 30+ native queries, with no behavioural gain. Statuses stay in the subclasses because the two enums differ and are ordinal-persisted.
2. **`Domains/OrderLine`** interface: `Item getItem()`, `int getQty()`, `double getSubtotal()`. Implemented by `PurchaseOrderLine` and `SalesOrderLine`.
3. **`Domains/Party`** (`@MappedSuperclass`, abstract): `name, street, city, state, zipcode, contactInfo` (column names unchanged, validation annotations moved from `Vendor` with them), `formattedAddress()` overloads, and `public abstract String getPartyType()` ("Vendor" / "Customer"). `Vendor extends Party`, `Customer extends Party`. `@Id` stays in the subclasses. Remove the bogus `@Column` on `formattedAddress()`.
4. **Exception hierarchy** (`Domains/Exceptions/`): `ResourceNotFoundException extends Exception` ← every existing `*NotFoundException` plus `GoodsReceiptNoteNotFoundException` and `PickingJobNotFoundException`. `OrderModificationException extends Exception` ← `ReceivedOrderModificationException`, `ShippedOrderModificationException`. Do not name the base `EntityNotFoundException` (it clashes with `jakarta.persistence`). The three `RuntimeException` user/auth exceptions are left alone. Keep every existing specific handler and optionally add one fallback per base type. `ShipmentNotFoundException` is re-parented later, in Prompt 9 (the OOM agent does not touch Shipment files).

Runtime polymorphism in **actual workflows**: `PurchaseOrderServiceImpl.updateById`, `SalesOrderServiceImpl.updateById`, both controllers' remove-row and delete guards, and a shared helper `Services/OrderGuard.requireModifiable(AbstractOrder<?> order)` all go through `AbstractOrder.isModifiable()`. Templates keep calling `order.total` (it resolves through `getTotal()`).

### 3.3 Compile-time polymorphism (overloads that are actually called)

`Stock.decrease(int)` / `decrease(int qty, String reason)` (the reason is logged); `addLine(line)` / `addLine(item, qty, price|cost)` on both orders; `Party.formattedAddress()` / `formattedAddress(String)`; existing `findAll()` / `findAll(Pageable)`. Owned by the Shipment agent: `ShipmentService.createShipment(...)` ×2 and `Shipment.transitionTo(...)` ×2. Every overload must have a production caller, not only a test caller.

### 3.4 Relationship classification and cascade decisions

| Relationship | UML kind | Mapping after Stage 4 |
|---|---|---|
| PurchaseOrder ◆— PurchaseOrderLine; SalesOrder ◆— SalesOrderLine; GoodsReceiptNote ◆— GoodsReceiptNoteLine; PickingJob ◆— PickingJobLine; Invoice ◆— InvoiceLine | **composition** | `cascade = ALL, orphanRemoval = true` (only after Stage 2 has removed every `set*Lines(newList)` call; grep first) |
| Shipment ◆— TrackingEvent | **composition** | `cascade = ALL, orphanRemoval = true` (Shipment agent) |
| Warehouse ◆— WarehouseSection | composition | `cascade = ALL`, **no** orphanRemoval (sections are referenced by stock) |
| Item ◆— ItemPrice, ItemCost (price/cost history) | composition | `cascade = ALL`, no orphanRemoval (referenced by order lines) |
| SalesOrder ◆— Backorder | composition | `cascade = ALL` (unchanged) |
| SalesOrder — PickingJob, Invoice; PurchaseOrder — GoodsReceiptNote | association (separate documents) | keep `cascade = ALL`. Deletion is only allowed while `isModifiable()`, so it only removes the pending GRN or picking job |
| Vendor ◇— Item | **aggregation** | `cascade = {PERSIST, MERGE}` (was ALL). Vendor delete is also blocked when the vendor has items → `?failedToDelete` |
| Vendor — PurchaseOrder; Customer — SalesOrder; Customer — Invoice | association | `cascade = {PERSIST, MERGE}` (was ALL). Customer delete is also blocked when it has sales orders |
| Item — {GoodsReceiptNoteLine, SalesOrderLine, PurchaseOrderLine, PickingJobLine, InvoiceLine, Backorder}; ItemPrice — SalesOrderLine; ItemCost — PurchaseOrderLine | association | **remove cascade**. `ItemController` delete guard extended: an item referenced by any line → `?failedToDelete` |
| WarehouseSection ◇— Stock; Stock as association class Item × WarehouseSection | aggregation / association class | unchanged (no cascade) |
| StockAdjustment → Item, source, destination; Shipment → SalesOrder, PickingJob | association (unidirectional) | unchanged / new |

Cascade is JPA-only, so there is no migration. Also remove the misleading `@Column` annotations on `@OneToMany` fields, and fix the field name `WarehouseSection.pickingJobLinesw` **only if** nothing references it (`mappedBy` is on the other side; check templates).

### 3.5 File ownership for the two parallel agents (Stage 3 ‖ Stage 4)

| | **Shipment agent (Prompt 5)** | **OOM agent (Prompts 6–8)** |
|---|---|---|
| Creates | `Domains/Shipment.java`, `ShipmentStatus.java`, `ShippingMethod.java`, `TrackingEvent.java`; `Domains/DTOs/NewShipmentDto.java`, `ShipmentStatusUpdateDto.java`; `Domains/Exceptions/ShipmentNotFoundException.java`, `ShipmentNotAllowedException.java`; `Repositories/ShipmentRepository.java`; `Services/ShipmentService.java`, `ShipmentServiceImpl.java`; `Controllers/ShipmentController.java`, `ShipmentExceptionHandler.java`; `templates/shipments/*`; `db/migration/V3__shipments.sql`; tests in group C1 (§5) | `Domains/AbstractOrder.java`, `OrderLine.java`, `Party.java`; `Domains/Exceptions/ResourceNotFoundException.java`, `OrderModificationException.java`; `Services/OrderGuard.java`; `db/migration/V4__*.sql` (only if needed); tests in group C2 (§5) |
| May edit | `templates/fragments/navbar.html`, `templates/pickingJobs/pickingJobDetails.html`, `templates/salesOrders/salesOrderDetails.html` (links only) | every existing entity in `Domains/` (annotations, superclass, domain methods), existing exceptions (parents only), `GlobalExceptionHandler.java`, existing services, controllers and `Bootstrap.java` where needed to call the new methods, existing tests only to adapt construction (never weaken assertions) |
| Must not touch | **any existing entity (including `SalesOrder.java`)**, `GlobalExceptionHandler.java`, `Bootstrap.java`, any existing service or controller | **any Shipment file listed in the left column**, any template, `V3__shipments.sql` |
| `SalesOrder.java` | **no edits**; uses the frozen API from §2.4; builds test data with `SalesOrder.builder()...status(...)`, never `setStatus` / `setId` | may change annotations, `extends AbstractOrder<SalesOrderLine>`, `@SuperBuilder`, hide `id`/`status` setters, add `addLine`, implement `getLines`/`isModifiable`/`getStatusLabel`. **Must keep**: table/column mappings, field names, `markPartiallyShipped()`, `markShipped()`, `getStatus()`, `getSaleOrderLines()`, `getBackorders()`, `getPickingJobs()`, `getCustomer()`, `getId()`, and the builder API including `.status(...)` |

The same freeze applies to `PickingJob`, `PickingJobLine` and `Customer`: the getters listed in §2.4 must survive Stage 4. Merge order after both finish: either, but `mvn -o test` must pass on the merged tree before Prompt 9 starts.

---

## 4. Design patterns (Prompt 9) and SOLID targets (Prompt 10)

Prompt 9 is the **integration point**. It is the first stage allowed to edit both Shipment and OOM files.

### 4.1 Patterns to implement and use (at least two are required; plan for four)

| Pattern | Classes | Where it is used |
|---|---|---|
| **Strategy** (stock adjustments) | `Services/StockAdjustmentHandler` (interface: `AdjustmentType type(); void apply(StockAdjustment)`); `CycleCountHandler`, `WriteOffHandler`, abstract `MoveStockHandler` ← `DamageHandler`, `TransferHandler` (all `@Component`) | `StockAdjustmentServiceImpl` gets a `List<StockAdjustmentHandler>` injected, builds an `EnumMap`, and fails fast at startup if a type has no handler. The `switch` is removed (OCP) |
| **Strategy** (shipping) | `Services/ShippingStrategy` (`ShippingMethod method(); double calculateCost(Shipment); LocalDate estimateDelivery(LocalDate dispatchDate)`); `StandardShippingStrategy`, `ExpressShippingStrategy`, `OvernightShippingStrategy` | `ShipmentServiceImpl.createShipment` resolves the strategy by `ShippingMethod` and fills `shippingCost` / `estimatedDeliveryDate` (columns reserved in V3). This is runtime polymorphism in a real workflow |
| **State** (lightweight) | `ShipmentStatus.allowedNext()` / `canTransitionTo` + `Shipment.transitionTo`; the order/GRN/picking `mark*` methods | already built in Prompts 4–5. Prompt 9 documents it and may move behaviour into per-constant enum bodies |
| **Facade** | `OrderFulfilmentService` (Prompt 4) | `PickingJobController` |
| **Observer** | `Domains/Events/ShipmentStatusChangedEvent` (or `Services/`), published via `ApplicationEventPublisher`; `Services/SalesOrderShippingStatusListener` (`@EventListener`, synchronous, same transaction) | replaces the direct `syncSalesOrderStatus` call in `ShipmentServiceImpl` |
| **Template Method** | `AbstractOrder.getTotal()` (Prompts 6–8) | totals on PO/SO pages |
| Existing (document only) | Repository, Service Layer, DTO, Builder, Specification, Registry (`SuggestionServiceImpl`), Facade (`SuggestionIndex`), Adapter (`UserServiceImpl` → `UserDetails`), Template Method (`TwoFactorAuthenticationSuccessHandler`) | — |

Also in Prompt 9: re-parent `ShipmentNotFoundException` under `ResourceNotFoundException`, and fold the `ShipmentExceptionHandler` mappings into `GlobalExceptionHandler` if that is cleaner.

### 4.2 SOLID targets (Prompt 10), each with a concrete code example

| Principle | Change | Evidence file |
|---|---|---|
| SRP | Fulfilment orchestration moved out of the controller (Prompt 4). GRN creation moved out of `PurchaseOrderServiceImpl.save` into `GoodsReceiptNoteService.findOrCreatePending` (Prompt 3). Optionally extract picking-job creation from `SalesOrderServiceImpl.save` into `PickingJobService.createFor(SalesOrder)` | `OrderFulfilmentServiceImpl`, `GoodsReceiptNoteImpl` |
| OCP | Adjustment handlers and shipping strategies: a new type is a new class, with no edit to the service | `StockAdjustmentServiceImpl`, `ShipmentServiceImpl` |
| LSP | `List<AbstractOrder<?>>` handled uniformly (`getTotal`, `isModifiable`) in `OrderGuard`; `Party` subclasses in a shared formatter; a test proves substitutability | `OrderGuard`, `AbstractOrderPolymorphismTest` |
| ISP | Split `PurchaseOrderReportingService` and `SalesOrderReportingService` (KPI/report methods) out of the fat interfaces. The existing impls implement both. `DashBoardController`/`ReportController` depend on the narrow ones (update `DashBoardControllerTest` mocks without weakening it) | `Services/*ReportingService.java` |
| DIP | `SearchController` depends on services, not on 3 repositories. Remove unused injected dependencies (`ItemService`, `WarehouseSectionService` in `GoodsReceiptNoteController`). Strategies are injected as interfaces | `SearchController`, `GoodsReceiptNoteController` |

---

## 5. Test plan (three independent groups)

Location: `src/test/java/com/example/warehouseManagement/<package>/`. Conventions:
- Service tests use `@DataJpaTest` + `@Import({...ServiceImpl.class, ...})` on Flyway H2. That is real repositories, with no Bootstrap run.
- Pure domain tests are plain JUnit.
- Controller tests use `@WebMvcTest` with the existing security mocks (`UserService`, `EmailService`, `TwoFactorAuthenticationSuccessHandler`), `@WithMockUser`, and `.with(csrf())` on POSTs.
- No test may be deleted or weakened. The existing 32 must keep passing.

### Group A: inventory/stock, supplier, purchase orders/GRN (Prompt 3)

| File | Covers |
|---|---|
| `Domains/StockTest` | `increase`/`decrease`, below-zero → `InsufficientStockException`, non-positive qty rejected |
| `Domains/PurchaseOrderStatusTest` | `registerReceipt` partial/full, illegal transition from RECEIVED, `isModifiable`, `isReceivable` |
| `Services/StockServiceImplTest` | rows (10, 5): pick 3 → total 12; pick 10 → total 2; full pick deletes the row; two lines of the same item; put-away by `stockId`, partial move merges, negative or missing destination rejected |
| `Services/StockAdjustmentServiceImplTest` | all 4 types; negative WRITE_OFF rejected; TRANSFER without destination or same section rejected with no partial change; CYCLE_COUNT with duplicate rows sets the bin total |
| `Services/GoodsReceiptNoteServiceImplTest` | full receipt → RECEIVED; one line 0 → PARTIALLY_RECEIVED; short on all lines → PARTIALLY_RECEIVED; two receipts complete the PO; all-zero → unchanged; negative / over-receipt / size mismatch rejected; two receipts of one item → one floor row; second fulfil of a FULFILLED GRN rejected |
| `Services/PurchaseOrderServiceImplTest` | save creates exactly one PENDING GRN with qty-0 lines; update of PARTIALLY_RECEIVED → `ReceivedOrderModificationException`; item change refreshes cost; shorter posted list → no 500 |
| `Repositories/GoodsReceiptNoteRepositoryTest` | the derived finders with 0, 1 and 2 GRNs per PO |
| `Repositories/StockRepositoryTest` (**extend**) | F17: qty-in-transit counts only IN_TRANSIT/PARTIALLY_RECEIVED POs |
| `Controllers/GoodsReceiptNoteControllerTest` | two GETs of `/receive` → one PENDING GRN; POST for PARTIALLY_RECEIVED processed; all-zero → `?nothingReceived`; RECEIVED → `?alreadyReceived` |
| `Controllers/PurchaseOrderControllerTest` | remove-row on RECEIVED → `?cannotBeUpdated` and the line still exists; delete PARTIALLY_RECEIVED → `?failedToDelete` |
| `Controllers/VendorControllerTest` | blank name → form re-rendered with errors; valid create/update redirects; unknown id → `?notFound`; delete with POs blocked |

### Group B: sales orders, picking, backorders (Prompt 4)

| File | Covers |
|---|---|
| `Domains/PickingJobLineTest` | `recordPick` remainder, negative and over-pick rejected |
| `Domains/SalesOrderStatusTest` | `markPartiallyShipped` / `markShipped` legal and illegal transitions; `isModifiable` |
| `Services/OrderFulfilmentServiceImplTest` | `[out, in, in]`, `[out, out, in]`, `[in, out, in]` paired by line id; stock 3 / qty 5 → picked 3, backorder 2, invoice line 3; entered 2 of 5 → backorder 3; full pick → no backorder; nothing picked → rejected and job PENDING; second fulfil → `PickingJobAlreadyFulfilledException` with stock, invoices and backorders unchanged; a failure in the stock step rolls everything back (job PENDING, no invoice) |
| `Services/BackorderServiceImplTest` | fulfilling the backorder job removes or reduces the existing row; a short backorder fulfilment does not duplicate it |
| `Services/SalesOrderServiceImplTest` | edit propagates qty/item to the pending picking job; edit after a fulfilled pick → `ShippedOrderModificationException`; item change refreshes price; null current price rejected; no hidden SHIPPED promotion |
| `Services/WarehouseSectionServiceImplTest` | `findByPickingJob` for a job holding only the second SO line → bins for that item at index 0 |
| `Services/InvoiceServiceImplTest` | no invoice when nothing was picked |
| `Controllers/PickingJobControllerTest` | POST to a FULFILLED job → `?alreadyFulfilled`; GET fulfil form for a FULFILLED job redirects; unknown id → `?notFound` |
| `Controllers/SalesOrderControllerTest` | delete unknown id → `?notFound` (no 500); remove-row on a non-modifiable SO → `?cannotBeUpdated` |

### Group C: shipment, OOM behaviour, strategies, events (Prompts 5–9)

| Sub-group / owner | File | Covers |
|---|---|---|
| C1 Shipment (Prompt 5) | `Domains/ShipmentStatusTest` | the full §2.3 table (every allowed pair true, every other pair false, terminal states) |
| C1 | `Domains/ShipmentTest` | `create` rejects a non-FULFILLED or empty job; `transitionTo` appends events and stamps dates; illegal transition throws; events list is unmodifiable |
| C1 | `Services/ShipmentServiceImplTest` | create from an eligible job; duplicate shipment for the same job rejected; tracking number unique; first dispatch → SO PARTIALLY_SHIPPED; all dispatched with no backorders → SO SHIPPED; CANCELLED before dispatch leaves the SO unchanged |
| C1 | `Repositories/ShipmentRepositoryTest` | V3 schema validates; finders; unique constraints |
| C1 | `Controllers/ShipmentControllerTest` | list, details, create POST with CSRF, invalid transition → `?invalidTransition`, unknown id → `?notFound`, track by number |
| C2 OOM (Prompts 6–8) | `Domains/AbstractOrderPolymorphismTest` | `List<AbstractOrder<?>>` with a PO and an SO: `getTotal()`, `isModifiable()`, `getStatusLabel()` dispatch |
| C2 | `Domains/PartyInheritanceTest` | `Vendor`/`Customer` as `Party`: `formattedAddress()` overloads, `getPartyType()` |
| C2 | `Domains/EntityEncapsulationTest` | `toString()`/`hashCode()` on a bidirectional SO↔line graph do not recurse; `addLine` sets the back-reference; no public `setStatus` (reflection check) |
| C2 | `Domains/Exceptions/ExceptionHierarchyTest` | `instanceof` hierarchy |
| C2 | `Controllers/GlobalExceptionHandlerTest` | every mapping, including new business-rule exceptions → correct redirect flag |
| C2 | `Services/CascadeBehaviourTest` (`@DataJpaTest`) | removing a line from an order deletes it (orphanRemoval); deleting a vendor with items is blocked or does not delete the items |
| C3 Patterns (Prompt 9) | `Services/StockAdjustmentHandlerTest` | one test per handler; every `AdjustmentType` has a handler |
| C3 | `Services/ShippingStrategyTest` | cost and ETA per method; resolver selects by `ShippingMethod` |
| C3 | `Services/ShipmentEventListenerTest` | the event published on transition updates the SO status through the listener |

Final gate (Stage 9): `WarehouseManagementApplicationTests` (context + Flyway `validate` with V3/V4), the full `mvn -o test`, and a manual `spring-boot:run` smoke test of the PO→GRN→put-away→SO→pick→ship→deliver flow.

---

## 6. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | **Ordinal enums.** Reordering `PoStatus`/`SoStatus`/`GrnStatus`/`PjStatus` silently corrupts data and the native SQL | Never add or reorder constants. Use new STRING enums for new concepts (C8) |
| R2 | **`ddl-auto=validate` failures** from new entities (type mismatch, missing `_SEQ`) | Mirror V2's style. `double`→`float(53)`, `LocalDateTime`→`timestamp(6)`, STRING enum→`varchar` + check. Every new entity needs `<table>_SEQ increment by 50`. Run `WarehouseManagementApplicationTests` after every entity change |
| R3 | **Migration number collision** between parallel agents | §2.6 reservations: Stage 2 none, Shipment V3, OOM V4, later stages V5+. Never edit V1/V2 |
| R4 | **Parallel-agent merge conflicts** on `SalesOrder.java`, `GlobalExceptionHandler.java`, `Bootstrap.java` and templates | §3.5 ownership. The Shipment agent edits no existing Java file at all, only 3 template links. The OOM agent edits no template |
| R5 | **`@SuperBuilder` migration** breaks builder calls in `Bootstrap`, the `*Pool` classes and the repository tests | Apply `@SuperBuilder` to the superclass and both subclasses together. Keep `@Builder.Default`. Compile and run the full test suite |
| R6 | **`orphanRemoval = true` + collection replacement** → "collection with cascade=all-delete-orphan was no longer referenced" | Stage 2 removes `setPickingJobLines(newList)` (`PickingJobServiceImpl` line 211). Stage 4 greps `set\w*Lines(` before enabling it, and mutates via `addLine`/`removeLine` |
| R7 | **Losing Lombok `equals`** changes `contains`/`remove(Object)`/template comparisons | Grep before switching. Compare ids. `EntityEncapsulationTest` |
| R8 | **Cascade reduction** turns silent cascading deletes into FK violations (500s) | Add delete guards (`?failedToDelete`) in `VendorController`, `CustomerController`, `ItemController` in the same change, with tests |
| R9 | **Hiding setters vs Spring MVC binding**: update forms bind entities, possibly including `id`/`status` | Check `th:field="*{id}"` / `*{status}` in every update template before hiding. `DataBinder` ignores non-writable properties, so a hidden `id` would break updates. Keep the `id` setter where forms bind it (Party subclasses) |
| R10 | **Index-paired forms** (GRN, picking, put-away) break when lines change | Stage 2 moves all three to id-based pairing (hidden ids in templates) |
| R11 | **Bootstrap runs in `@SpringBootTest`** and seeds data the KPIs assert on | Do not change seed counts or statuses. Shipments are not seeded (or only via a separate seeder that adds no SO/PO rows) |
| R12 | **CSRF and security in new MockMvc tests** | `.with(csrf())`, `@WithMockUser`, and the existing `@MockBean` security trio |
| R13 | **Offline build** | No new dependencies. Bean Validation, Mockito, AssertJ and `@DataJpaTest` are already on the classpath. Never run `./mvnw` or `mvn clean` |
| R14 | **SO status semantics change** (C4): orders stay PENDING after picking until a shipment is dispatched | Document it in the README and the UML activity diagram. Shipment tests prove the SO reaches SHIPPED |
| R15 | **Concurrency** (F18): no `@Version` | Documented limitation. Status guards cover sequential double-submits |
| R16 | **UML drift**: diagrams drawn before Stages 2–5 finish would be wrong | UML (Stage 8) is drawn **after** Prompt 10, from the final code, following the 02c checklist |
