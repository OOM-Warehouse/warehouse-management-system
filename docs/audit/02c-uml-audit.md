# 02c — UML & Documentation Audit

Scope: read-only audit of the existing code base to collect what is needed for the use-case, class, sequence and activity diagrams. No source, test or config files were modified. Everything below comes from the code as it is now (branch `claude/spring-warehouse-refactor-b6ffc5`, HEAD `3143c03`). Where something is a suspicion rather than a confirmed fact, it says so.

Package root: `src/main/java/com/example/warehouseManagement/` (shortened to `…/` below).

---

## 1. Existing documentation and UML artefacts

| Artefact | Present? | Notes |
|---|---|---|
| `README.md` | Yes (177 lines) | Sections: Highlights, Screenshots, Run it, email config, Useful commands, **Core domain workflow** (5-step PO→GRN→Stock→Put-away / SO→Picking→Invoice/Backorder chain), Project layout, Gotchas, License. It has no UML. |
| `Warehouse_Management_System_DB.pdf` | Yes, 1 page | An **ER/DB diagram**, not UML. Its tables are `vendor, item, cost, price, purchase_order, purchase_orders_line, goods_receipt_note, goods_receipt_notes_line, customers, sales_order, sales_orders_line, picking_job, invoice, invoice_line, back_order, stocks, warehouses, warehouse_sections`. It is **out of date** compared with the code: the names differ (`customers` vs `customer`, `stocks` vs `stock`, `back_order` vs `backorder`, `cost/price` vs `item_cost/item_price`), the old `address` column has since been split into street/city/state/zipcode, and it lacks `app_user`, `stock_adjustment` and `picking_job_line`. Use it only as a historical reference. |
| `requirements.docx` | Yes (root) | Not opened by this audit. It is the assignment brief. |
| `docs/` | Yes | Contains only `docs/screenshots/01..07-*.png` (login, dashboard, sidebar, admin users, 2FA, forgot password). |
| UML files (`*.puml`, `*.drawio`, `*.mmd`, `*.uml`) | **None** | No use-case, class, sequence or activity diagram exists anywhere in the repo. All four have to be authored. |
| Flyway migrations | `src/main/resources/db/migration/V1__baseline.sql` (20 tables) and `V2__stock_adjustments.sql` (`stock_adjustment`) | These are the authoritative schema. Use them, not the PDF, for any ER cross-check. |

---

## 2. Actors and permissions (use-case diagram)

Source: `…/Security/SecurityConfig.java`, `…/Domains/User.java` (`enum Role { USER, ADMIN }`), `…/Security/TwoFactorAuthenticationSuccessHandler.java`, `templates/fragments/navbar.html`.

Authorisation rules (exact):
- `permitAll`: `/login`, `/verify-2fa`, `/forgot-password`, `/reset-password`, static assets, `/h2-console/**`.
- `/admin/**` requires `hasRole("ADMIN")`.
- `anyRequest().authenticated()`: every other feature is open to **any authenticated user** (USER or ADMIN).
- No `@PreAuthorize` or method-level security anywhere. The navbar hides the Admin link with `sec:authorize="hasRole('ADMIN')"`.
- Seeded accounts (`Bootstrap.java` ~l.478): `admin` (ROLE_ADMIN, no 2FA) and `manager` (ROLE_USER, 2FA on).

Recommended actors. Stay faithful to the code and do not invent extra roles:

| Actor | Basis in code | Use cases |
|---|---|---|
| **Guest / Unauthenticated user** | permitAll endpoints | Log in, Verify 2FA code, Request password reset, Reset password |
| **Warehouse User** (ROLE_USER) | `anyRequest().authenticated()` | All business use cases below |
| **Administrator** (ROLE_ADMIN) | `/admin/**` | Everything a Warehouse User can do, **plus** Manage users. Model it as a generalisation: Administrator → Warehouse User |
| **Email system** (secondary, external) | `EmailServiceImpl` uses `JavaMailSender` | Receives 2FA codes and reset links |

The code does **not** separate a Clerk, Picker, Receiver, Purchaser or similar role. If the diagram shows "Receiving clerk" or "Picker", label them as informal personas of the single Warehouse User role, or leave them out. Do not present them as security roles.

### Use cases found (controllers and templates)

| Package / subject | Use case | Controller (base path) | Templates |
|---|---|---|---|
| Authentication | Log in; Verify 2FA (`<<extend>>` Log in when `twoFactorEnabled`); Forgot password; Reset password; Log out | `AuthController`, `SecurityConfig` | `auth/*` |
| Administration | List/create/view/update/delete user (self-delete blocked) | `AdminUserController` `/admin/users` | `admin/*` |
| Dashboard | View KPIs (open SO/PO, pending picking/GRN, stock on floor, top movers, reorder candidates, vendor spend, aging POs) | `DashBoardController` `/` | `dashboard/dashboard.html` |
| Supplier management | CRUD Vendor, advanced search | `VendorController` `/vendors` | `vendors/*` |
| Customer management | CRUD Customer, advanced search | `CustomerController` `/customers` | `customers/*` |
| Inventory / catalogue | CRUD Item, advanced search; manage Item Price (create/view/update/delete) | `ItemController` `/items`, `ItemPriceController` `/items/{itemId}/itemPrices` | `items/*`, `itemPrices/*` |
| Procurement | Create PO (add/remove row), list/pending/advanced search, view, update (blocked if RECEIVED), delete | `PurchaseOrderController` `/purchase-orders` | `purchaseOrders/*` |
| Inbound shipment / receiving | Search PO, start receiving (creates GRN), fulfil GRN, list pending/fulfilled GRNs, view GRN | `GoodsReceiptNoteController` `/goods-receipt-notes` | `goodsReceiptNotes/*` |
| Put-away | Put away stock from floor; put away from a given section | `PutAwayController` `/put-aways` | `putAwayTasks/*` |
| Stock adjustment | Record CYCLE_COUNT / DAMAGE / WRITE_OFF / TRANSFER; list | `StockAdjustmentController` `/stock-adjustments` | `stockAdjustments/*` |
| Order fulfilment | Create SO (auto-creates Picking Job), list/pending/advanced search, view, update (blocked if SHIPPED), delete | `SalesOrderController` `/sales-orders` | `salesOrders/*` |
| Picking / outbound | List/search picking jobs, view, fulfil picking job (`<<include>>` Update stock, Create invoice; `<<extend>>` Create backorder) | `PickingJobController` `/picking-jobs` | `pickingJobs/*` |
| Reporting | SO by customer, SO details, pending SO by year/month, stock level by vendor, backorders by year (each printable) | `ReportController` `/reports` | `reports/*` |
| Search / autocomplete | Global search; type-ahead suggestions (JSON) | `SearchController` `/search`, `SuggestionController` `/api/suggestions` | `search/results.html` |

Legacy and dead artefacts to leave out of the diagrams: `…/Services/GoodsReceiptNoteImplBack.java` (fully commented out), `templates/forms/fulfillGoodsReceiptNoteFormBack.html`, `forms/newItem.html`, `forms/newVendor.html`, and the commented-out GET mapping at the bottom of `PickingJobController`.

---

## 3. Class diagram material

### 3.1 Entities (`…/Domains`). All use Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor` and `GenerationType.AUTO` `Long id`.

| Entity (table) | Key attributes | Domain methods / enums |
|---|---|---|
| `Vendor` (vendor) | name, street, city, state, zipcode, contactInfo | `formattedAddress()` |
| `Customer` (customer) | same address fields as Vendor | `formattedAddress()` |
| `Item` (item) | sku:int, description | n/a |
| `ItemPrice` (item_price) | start, end:LocalDate, price:double | `formattedEndDate()` |
| `ItemCost` (item_cost) | start, end, cost:double | n/a |
| `PurchaseOrder` (purchase_order) | date, status:PoStatus | `getTotal()`; `enum PoStatus {IN_TRANSIT(0), RECEIVED(1), PARTIALLY_RECEIVED(2)}` with `pk` field |
| `PurchaseOrderLine` | qty | `getSubtotal()` |
| `GoodsReceiptNote` | date, status:GrnStatus | `getTotalQty()`; `enum GrnStatus {PENDING, FULFILLED}` |
| `GoodsReceiptNoteLine` | qty, notes | n/a |
| `SalesOrder` (sales_order) | date, status:SoStatus | `getTotal()`; `enum SoStatus {PENDING, PARTIALLY_SHIPPED, SHIPPED}` |
| `SalesOrderLine` | qty | `getSubtotal()` |
| `PickingJob` | date, status:PjStatus | `enum PjStatus {PENDING, FULFILLED}` |
| `PickingJobLine` | qtyToPick, qtyPicked | n/a |
| `Invoice` | date | n/a |
| `InvoiceLine` | qty | n/a |
| `Backorder` | qty | n/a |
| `Warehouse` | address | n/a |
| `WarehouseSection` | sectionNumber (floor = `"00-00-0-0"`) | n/a |
| `Stock` (stock) | qtyOnHand | association class Item × WarehouseSection |
| `StockAdjustment` (V2) | type:AdjustmentType (`@Enumerated(STRING)`), qty, adjustmentDate, notes | `enum AdjustmentType {CYCLE_COUNT, DAMAGE, WRITE_OFF, TRANSFER}` |
| `User` (app_user) | username, email, password (BCrypt), role, enabled, twoFactorEnabled, twoFactorCode(+ExpiresAt), passwordResetToken(+ExpiresAt), createdAt, lastLoginAt | `enum Role {USER, ADMIN}` |

Non-entity domain classes: `LastThreeMonthsSales` (`getThreeMonthsAverageSales()`, `getMaxValue()`), `State`, and the seed holders `CustomerPool`, `VendorPool`, `ItemPool` and `StatePool`. Each of the four seed holders has a private constructor and static data. About 50 DTOs live in `Domains/DTOs` (form-backing and query projections). Leave the DTOs out of the main class diagram, apart from `PickingJobDto` and `GoodsReceiptNoteDto` if a sequence diagram needs them.

Enum persistence caveat: `PoStatus`, `GrnStatus`, `SoStatus` and `PjStatus` have **no `@Enumerated`**, so they are stored as ORDINAL. (The code comment `PARTIALLY_SHIPPED //2` is wrong. Its ordinal is 1.)

### 3.2 Relationships. Every one of them was verified against the mapping annotations.

There is **no `orphanRemoval` anywhere** in the code base. All collection sides are `@OneToMany(mappedBy=…, cascade = CascadeType.ALL, fetch = LAZY)`, except the two on `WarehouseSection`, which have no cascade. The owning side (FK) is always the `@ManyToOne` child. Every association is **bidirectional** when a collection exists, and otherwise unidirectional from the child.

| Parent (1) | Child (*) | FK on child | Cascade from parent | Suggested UML notation |
|---|---|---|---|---|
| PurchaseOrder | PurchaseOrderLine | purchase_order_id | ALL | **Composition** (a line has no meaning without its order. Note that the absence of orphanRemoval means JPA does not enforce it fully.) |
| SalesOrder | SalesOrderLine | sales_order_id | ALL | **Composition** (same caveat) |
| GoodsReceiptNote | GoodsReceiptNoteLine | goods_receipt_note_id | ALL | **Composition** |
| PickingJob | PickingJobLine | picking_job_id | ALL | **Composition** |
| Invoice | InvoiceLine | invoice_id | ALL | **Composition** |
| Warehouse | WarehouseSection | warehouse_id | ALL | **Composition** |
| PurchaseOrder | GoodsReceiptNote | purchase_order_id | ALL | Association 1 → 0..* (or aggregation). A GRN is a separate document. |
| SalesOrder | PickingJob | sales_order_id | ALL | Association 1 → 1..* (one is created on save, more for backorders) |
| SalesOrder | Invoice | sales_order_id | ALL | Association 1 → 0..* |
| SalesOrder | Backorder | sales_order_id | ALL | Association 1 → 0..* |
| Customer | SalesOrder | customer_id | ALL | Association 1 → 0..* |
| Customer | Invoice | customer_id | ALL | Association 1 → 0..* |
| Vendor | Item | vendor_id | ALL | **Aggregation** (vendor supplies items) |
| Vendor | PurchaseOrder | vendor_id | ALL | Association 1 → 0..* |
| Item | ItemPrice | item_id | ALL | Composition (price history belongs to the item) |
| Item | ItemCost | item_id | ALL | Composition (cost history belongs to the item) |
| Item | GoodsReceiptNoteLine, SalesOrderLine, PurchaseOrderLine, PickingJobLine, InvoiceLine, Backorder | item_id | ALL (on all six collections) | Plain **association** (line → item, many-to-one). The cascade is a code smell, not a semantic composition (see 3.4). |
| ItemPrice | SalesOrderLine | item_price_id | ALL | Association (the line records the price in effect) |
| ItemCost | PurchaseOrderLine | item_cost_id | ALL | Association |
| WarehouseSection | Stock | warehouse_section_id | **none** | Association |
| WarehouseSection | PickingJobLine (`pickingJobLinesw`) | warehouse_section_id | **none** | Association (the bin a line was picked from) |
| Item | Stock | item_id | (unidirectional; Item has no `stocks` list) | Association. `Stock` is effectively an association class between Item and WarehouseSection with attribute `qtyOnHand`. |
| StockAdjustment | Item, WarehouseSection `source` (not null), WarehouseSection `destination` (nullable) | item_id, source_warehouse_section_id, destination_warehouse_section_id | unidirectional | Associations with role names `source` and `destination` |
| User | (none) | n/a | n/a | Standalone. It has no link to orders or adjustments, so do not draw a "created by" association. |

### 3.3 Inheritance, interfaces, polymorphism (what actually exists)

- **No entity inheritance.** No `@MappedSuperclass`, no `@Inheritance`, no abstract classes in the code base.
- **Interface realisation (runtime polymorphism):** every service is an interface plus an implementation. The pairs are Backorder, Customer, Email, Invoice, Item, ItemCost, ItemPrice, PickingJob, PurchaseOrder, PurchaseOrderLine, SaleOrderLine, SalesOrder, StockAdjustment, Stock, Suggestion, User and WarehouseSection (`XService` ← `XServiceImpl`). There are two naming exceptions. The GRN pair is `GoodsReceiptNoteService` ← `GoodsReceiptNoteImpl`, and the GRN line pair is `GoodsReceiptNoteLineService` ← `GoodsReceiptNoteLineServiceImpl`. Controllers depend on the interfaces (constructor injection).
- **Framework inheritance:** `UserService extends UserDetailsService`; `TwoFactorAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler` (overrides `onAuthenticationSuccess`, a good runtime-polymorphism example); `Bootstrap implements CommandLineRunner`; repositories `extends CrudRepository/JpaRepository` (+ `JpaSpecificationExecutor` where advanced search exists).
- **Exception hierarchy:** checked exceptions `extends Exception`: Customer/Vendor/Item/ItemPrice/SalesOrder/PurchaseOrder NotFound, ShippedOrderModification, ReceivedOrderModification. Unchecked exceptions `extends RuntimeException`: DuplicateUser, InvalidToken, UserNotFound.
- **Generics:** `DSA/BinarySearchTree<T extends Comparable<T>>`; `DSA/Trie`, wrapped by `Services/SuggestionIndex` (composition) and used by `SuggestionServiceImpl`. `SuggestionService` declares nested `enum Type` and `enum Mode`.
- **Compile-time polymorphism:** verify which overloads exist before drawing them. Repositories have overloaded `findAll(...)` variants (`findAll()`, `findAll(Pageable)`, `findAll(Specification, Pageable)`), and the services expose `findAll()` and `findAll(Pageable)` (e.g. `SalesOrderServiceImpl`, `PurchaseOrderServiceImpl`).
- **Natural `@MappedSuperclass` candidates (do not draw them as existing):** Vendor and Customer share id, name, street, city, state, zipcode, contactInfo and `formattedAddress()` verbatim. All the "document" entities (PO, SO, GRN, PickingJob, Invoice) share `id` and `date`, and most have a `status` enum. If a later prompt introduces e.g. `AbstractParty` or `AbstractDocument`, the class diagram must be redrawn from the new code.

### 3.4 Modelling cautions (do not paper over these in the diagrams)

1. `Item` cascades ALL to its six line/backorder collections and `Vendor` cascades ALL to `Item`/`PurchaseOrder`, so deleting a Vendor or Item cascades into transactional history. In UML, draw these as associations, not compositions. Mention the cascade only as an implementation note.
2. `@Column(name=…)` sits on several `@OneToMany` fields (e.g. `@Column(name = "invoices")`), and on the `formattedAddress()` methods of Customer/Vendor. Hibernate ignores it and it has no schema meaning. Ignore it when deriving attributes.
3. `WarehouseSection.pickingJobLinesw` is a typo in the field name (the trailing `w`). The class diagram should show the real name, or note it.
4. `SalesOrder.saleOrderLines` (not `salesOrderLines`) is the real field name.

---

## 4. Important service interactions (sequence diagram material)

| Trigger (controller method) | Service calls (in order) | Repositories touched | Transaction boundary |
|---|---|---|---|
| `SalesOrderController` POST `/sales-orders/new?save` | `SalesOrderService.save(so)` | SalesOrder, PickingJob, ItemPrice (`findCurrentItemPriceByItemId`), SaleOrderLine, PickingJobLine | single `@Transactional` in service |
| `PurchaseOrderController` POST `/purchase-orders/new?save` | `PurchaseOrderService.save(po)`, which also creates a PENDING GRN with lines qty = PO qty | PurchaseOrder, ItemCost (`findCurrentItemCostByItemId`), GoodsReceiptNote, GoodsReceiptNoteLine, PurchaseOrderLine | single `@Transactional` |
| `GoodsReceiptNoteController.startReceivingShipment` (GET) | `PurchaseOrderService.findById` → `GoodsReceiptNoteService.create(po)` (**creates a second GRN**, lines qty 0) → `addGoodReceiptNoteLines` | PO, GRN, GRN line | none on `create` |
| `GoodsReceiptNoteController.finishReceivingShipment` (POST) | `PurchaseOrderService.findById` → `GoodsReceiptNoteService.findByPurchaseOrder` → `fulfill(grn, dto)` | WarehouseSection (`findBySectionNumber("00-00-0-0")`), Stock (new row per received line), GRN line, GRN | `fulfill` is `@Transactional` |
| `PutAwayController.fulfillPutAwayTask` (POST) | `StockService.findStocksOnFloor` → `putAwayStocks(wrapper, stocks)` | Stock, WarehouseSection | `@Transactional` |
| `PickingJobController.fulfillPickingJob` (POST) | `PickingJobService.findById` → `PickingJobService.fulfill(pj, dto)` → `StockService.pickStock(updatedPj)` → `InvoiceService.createByPickingJob(pj)` | PickingJob, PickingJobLine, Stock, WarehouseSection, Backorder, SalesOrder, Invoice, InvoiceLine | **three separate transactions**. Only `fulfill` is `@Transactional`, and the controller orchestrates the rest. |
| `StockAdjustmentController.save` (POST) | `ItemService.findById`, `WarehouseSectionService.findById` ×2 → `StockAdjustmentService.apply(adj)` | Stock, StockAdjustment | `@Transactional`; `IllegalStateException` → `?failed` |
| Login (Spring Security) | `UserServiceImpl.loadUserByUsername` → `TwoFactorAuthenticationSuccessHandler.onAuthenticationSuccess` → [2FA on] `UserService.generateTwoFactorCode` + `EmailService.sendTwoFactorCode`, demote context, redirect `/verify-2fa` → `AuthController.verify2faSubmit` → `verifyTwoFactorCode` → build `UsernamePasswordAuthenticationToken`, save context, `recordSuccessfulLogin` | User | n/a |
| Forgot / reset password | `findByEmail` → `generatePasswordResetToken` → `EmailService.sendPasswordResetLink`; then `findByValidResetToken` → `resetPassword` | User | n/a |

Exception-handling flow (`…/Controllers/GlobalExceptionHandler.java`, `@ControllerAdvice`): it maps the six NotFound/Modification checked exceptions to `redirect:/<module>?notFound|cannotBeUpdated`, and `MethodArgumentTypeMismatchException` to `redirect:<uri>?invalidFilter`. `AdminUserController` catches `DuplicateUserException`/`UserNotFoundException` locally. Error pages are `templates/error/403|404|500.html`.

---

## 5. Main business workflows, decision points and alternative flows

### W1 Order fulfilment / picking (`PickingJobServiceImpl.fulfill`, `StockServiceImpl.pickStock`, `InvoiceServiceImpl.createByPickingJob`)
1. User opens `/picking-jobs/{id}/fulfill`. If the job is not found → `redirect:/picking-jobs?notFound`.
2. For each picking job line, the user selects a bin (`warehouseSectionService.findBySalesOrder`) and enters qtyPicked.
3. **Decision per line:** `stock == null || sectionId == 0 || qtyPicked == 0`?
   - Yes → the line is collected for backorder and deleted from the job.
   - No → **decision:** `stock.qtyOnHand >= qtyPicked`? Yes → qtyPicked as entered. No → qtyPicked = qtyOnHand (a short pick, which is *not* backordered).
4. **Decision:** any backorder lines? Yes → create a new PENDING PickingJob for the same SO with the unmet lines, and create a `Backorder` per line linked to the SO.
5. Job → FULFILLED.
6. `pickStock`: for each line, if `remaining > 0` update the stock, else delete the stock row. Lines with no stock row are skipped.
7. `createByPickingJob`: create an Invoice for the SO's customer with an InvoiceLine for every line with qtyPicked > 0.
8. `redirect:/picking-jobs?fulfilled`.

### W2 Inbound receiving (`GoodsReceiptNoteController`, `GoodsReceiptNoteImpl.fulfill`)
1. Search a PO by number. Not found → `?notFound`.
2. Start receiving: **decision** on PO status. RECEIVED → `?alreadyReceived`. Otherwise create a GRN and show the form.
3. Submit: **decision** on status. Only `IN_TRANSIT` proceeds. RECEIVED → `?alreadyReceived`. Anything else (including PARTIALLY_RECEIVED) → `?notFound`.
4. Per line: **decision** `qty == 0` → skip. Otherwise record qty and notes, and create a Stock row on the floor section `00-00-0-0`.
5. **Decision:** no lines received → return null (the controller still redirects `?received`). Fewer than all lines → set PARTIALLY_RECEIVED, *then unconditionally overwritten to RECEIVED* (see section 7).
6. GRN → FULFILLED.

### W3 Put-away (`StockServiceImpl.putAwayStocks`)
Per floor stock row: **decision** `qtyToMove == 0` → skip. **Decision** `qtyToMove >= qtyOnHand` → move the whole row (change section). Otherwise split it into a new Stock row at the destination and decrement the source.

### W4 Stock adjustment (`StockAdjustmentServiceImpl.apply`)
A switch on `AdjustmentType`:
- CYCLE_COUNT → set the absolute qty, creating the row if absent.
- WRITE_OFF → decrement the source. It throws `IllegalStateException` if there is no stock or the result would be negative, and deletes the row at 0.
- DAMAGE / TRANSFER → decrement the source **and** increment the destination.

The controller has these alternatives: item/source missing → `?invalid`; `IllegalStateException` → `?failed`; success → `?applied`. This is a strong candidate for a Strategy-pattern refactor, and a clean activity diagram with a 4-way decision node.

### W5 Sales order lifecycle
- Create: `save` persists the SO, assigns the *current* ItemPrice per line, and auto-creates a PENDING PickingJob mirroring every line.
- Update (`updateById`): not found → `SalesOrderNotFoundException`. SHIPPED → `ShippedOrderModificationException` (→ `?cannotBeUpdated`). If PARTIALLY_SHIPPED with no backorders → SHIPPED. Then customer and line changes are applied, and new lines get the current price.
- Delete: the controller blocks it when SHIPPED.

### W6 Purchase order lifecycle
- Create: `save` assigns the current ItemCost per line and auto-creates a PENDING GRN.
- Update: not found → `PurchaseOrderNotFoundException`. RECEIVED → `ReceivedOrderModificationException`.

### W7 Authentication with 2FA and password reset
See section 4. Decision points: credentials valid? → 2FA enabled? → code valid, user enabled and pending session attribute present? Forgot password always shows the same banner, so whether the email exists is not leaked. The reset token must be valid, and the password and confirmation must match.

### W8 User administration (ADMIN only)
Create/update with a duplicate username or email → `DuplicateUserException`, and the form is re-rendered. Delete is blocked for the currently logged-in admin, and `?notFound` is shown if the user is absent.

---

## 6. Recommended diagrams

### Sequence diagrams (best candidates, in priority order)
1. **Fulfil Picking Job (W1)**. This is the strongest choice. It involves the most collaborators (Controller → PickingJobService → Stock/PickingJobLine/Backorder/SalesOrder repositories → StockService → InvoiceService) and has `alt` (stock available / short pick / out-of-stock), `opt` (backorders exist) and `loop` (per line) fragments. It covers both order fulfilment and inventory tracking.
2. **Receive Purchase Order / GRN fulfilment (W2)**. This covers supplier and shipment tracking (PO status IN_TRANSIT → RECEIVED) and stock creation on the floor.
3. **Login with 2FA (W7)**. It shows the framework extension point (overridden `onAuthenticationSuccess`) and the external Email actor.
4. (Optional) **Create Sales Order (W5)**, a simpler one showing the auto-generated PickingJob.

### Activity diagrams (best candidates)
1. **End-to-end warehouse flow**, with swimlanes for the Warehouse User and the System. The chain is PO → GRN (receive to floor) → Put-away → SO → Picking job → Stock decrement → Invoice → [backorder?] → new picking job. It matches the README's "Core domain workflow".
2. **Picking-job fulfilment detail (W1)**, with the per-line decision diamonds from section 5.
3. **Stock adjustment (W4)**, with a 4-way branch on AdjustmentType, guard conditions, and an exception exit.

### Use-case diagram
One system boundary with the four actors from section 2. Include `<<include>>` from *Fulfil picking job* to *Update stock* and *Generate invoice*, and `<<extend>>` from *Create backorder* to *Fulfil picking job*. Add `<<include>>` from *Create sales order* to *Generate picking job*, and from *Create purchase order* to *Open GRN*. Add `<<extend>>` from *Verify 2FA* to *Log in*. Draw the Administrator → Warehouse User generalisation.

### Class diagram
Group the classes into Party (Vendor, Customer), Catalogue (Item, ItemPrice, ItemCost), Procurement (PO, POLine, GRN, GRNLine), Sales (SO, SOLine, PickingJob, PJLine, Invoice, InvoiceLine, Backorder), Warehouse (Warehouse, WarehouseSection, Stock, StockAdjustment) and Security (User). Use the multiplicities and composition/association choices from section 3.2. A second, smaller diagram can show service interfaces and their implementations, `GlobalExceptionHandler` and the exception hierarchy.

---

## 7. Discrepancies and defects found while tracing the workflows

These affect what the diagrams can truthfully claim. They were not fixed (this task is read-only).

| # | Location | Finding | Confidence |
|---|---|---|---|
| D1 | `GoodsReceiptNoteImpl.fulfill` | `setStatus(PARTIALLY_RECEIVED)` is immediately followed by an unconditional `setStatus(RECEIVED)`, so PARTIALLY_RECEIVED is never persisted by the receiving workflow. | Confirmed by reading |
| D2 | `PurchaseOrderServiceImpl.save` + `GoodsReceiptNoteImpl.create` | A GRN is created when the PO is saved, and **another** one is created on the "start receiving" GET. `GoodsReceiptNoteRepository.findByPurchaseOrder` is a native query returning `Optional<…>`, so with 2+ GRNs per PO it is expected to fail with a non-unique result. The native query's aliases (`"purchaseOrder"`) also do not match the entity's column names. | Suspected; needs a runtime test |
| D3 | `PickingJobServiceImpl.fulfill` | `pickingJob.getPickingJobLines().remove(i)` inside an index-based `for` loop skips the element after each removal, and misaligns the following lines with `pickingJobDto.getPickingJobDtoLines().get(i)`. | Confirmed by reading |
| D4 | Whole code base | `SalesOrder.status` is never moved to PARTIALLY_SHIPPED or SHIPPED by the fulfilment workflow. Only `Bootstrap` seeds those values, and `updateById` promotes PARTIALLY_SHIPPED to SHIPPED. Outbound "shipment tracking" in a state diagram would therefore be aspirational. | Confirmed by grep |
| D5 | `PickingJobController.fulfillPickingJob` | Fulfil, stock decrement and invoice creation run in three separate transactions orchestrated by the controller, so a failure midway leaves partial state. | Confirmed by reading |
| D6 | `StockAdjustmentServiceImpl.apply` | DAMAGE/TRANSFER call `incrementAt(destination)`, but `destination` is nullable and the controller does not require it. A null destination would throw `NullPointerException`, which is not caught (only `IllegalStateException` is caught), so the user would get a 500 instead of `?failed`. DAMAGE also moves stock to a destination rather than reducing it, which is a semantic question. | Likely; not executed |
| D7 | `GoodsReceiptNoteController.finishReceivingShipment` | A PARTIALLY_RECEIVED PO falls into the `else` branch and redirects to `?notFound`. | Confirmed by reading |
| D8 | Short picks (W1 step 3) | When `qtyOnHand < qtyPicked`, the shortfall is silently dropped. No backorder is created for the remainder. | Confirmed by reading |
| D9 | Enums | The status enums are stored as ORDINAL, and the `SoStatus` comment ordinals are wrong. Reordering any enum would corrupt the data. | Confirmed |
| D10 | `InvoiceServiceImpl.createByPickingJob` | Uses `System.out.printf` debug output. | Cosmetic |
| D11 | `Warehouse_Management_System_DB.pdf` | The ER diagram is out of date compared with the Flyway schema (section 1). | Confirmed |

---

## 8. Checklist for whoever draws the diagrams

- Use the **Flyway schema plus the entity annotations** as the source of truth, not the PDF.
- The actors are Guest, Warehouse User (ROLE_USER), Administrator (ROLE_ADMIN, which generalises User) and the external Email system. Do not add roles that are not in `User.Role`.
- Draw composition only for document→line and Warehouse→Section (plus Item→price/cost history). Draw everything else as association or aggregation. Do not claim `orphanRemoval`.
- Show interface realisation for every service, the two framework overrides (success handler, `CommandLineRunner`) and the exception hierarchy. Do **not** draw entity inheritance, because none exists yet.
- For the sequence and activity diagrams, model the *current* behaviour. If D1, D3, D4 and D6 are fixed by a later prompt, update the PARTIALLY_RECEIVED, backorder-loop, SO-status and DAMAGE branches to match.
