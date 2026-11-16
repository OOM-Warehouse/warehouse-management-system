# 02A — Functional Correctness Audit

Scope: purchase order (PO) processing, goods receipt notes (GRNs), partial goods receipt, sales order (SO) processing, picking jobs, partial stock availability, backorder generation, inventory updates, warehouse stock movements (put-away, stock adjustments), and shipment/delivery.

Method: I read the code statically on branch `claude/spring-warehouse-refactor-b6ffc5`, at HEAD `3143c03`. I did not change any source, test or config file. Line numbers are the current file lines. Each finding is marked one of two ways:

* **Confirmed (static).** The defect follows directly from the quoted code. No runtime assumption is needed.
* **Plausible.** This depends on Hibernate or Thymeleaf behaviour at runtime. A test should confirm it before it is fixed.

Test coverage: `src/test` has no service-level test for `GoodsReceiptNoteImpl.fulfill`, `PickingJobServiceImpl.fulfill`, `StockServiceImpl.pickStock`, `StockServiceImpl.putAwayStocks`, `StockAdjustmentServiceImpl.apply` or `InvoiceServiceImpl.createByPickingJob`. The existing tests are repository/controller tests: Backorder, PurchaseOrder, SalesOrder, Stock, DashBoard, Auth and AdminUser. So none of the defects below is currently caught by a test.

Severity scale:

| Severity | Meaning |
|---|---|
| **Critical** | Inventory or order state is silently corrupted in the normal path. |
| **High** | Corruption or a broken workflow in a common alternative path, or a missing core business rule. |
| **Medium** | Corruption that needs invalid or tampered input, or a less common path. Also 500 errors instead of handled errors. |
| **Low / Info** | Robustness, hygiene or dead code. |

---

## Summary

| # | Area | Finding | Severity | Status |
|---|---|---|---|---|
| F1 | GRN | `fulfill()` always marks the PO `RECEIVED`. "Partial" is decided per line, never per quantity. | Critical | Confirmed |
| F2 | GRN | A partially received PO can never be received again. Empty receipts are reported as success. | High | Confirmed |
| F3 | GRN | One PO gets several GRNs: one from `PurchaseOrderServiceImpl.save`, plus one per GET of `/receive`. `findByPurchaseOrder` returns an `Optional` and breaks when there are several. | High | Confirmed (plus one Plausible mapping issue) |
| F4 | GRN | Receipt lines are not validated: negative qty, over-receipt, DTO size, missing floor. Every receipt adds a new `Stock` row. | Medium | Confirmed |
| F5 | Picking | `fulfill()` removes from the list it is iterating by index. A line is skipped and the DTO lines become misaligned. | Critical | Confirmed |
| F6 | Picking / backorder | A partial pick (stock < requested, or picked < ordered) creates no backorder. Over-picking is allowed. | High | Confirmed |
| F7 | Picking / stock | A negative `qtyPicked` passes the check and increases stock. | Medium | Confirmed |
| F8 | Stock | `pickStock` reads TOP 1 row but updates or deletes **all** rows for (section, item). This creates phantom stock or loses stock. | Critical | Confirmed |
| F9 | Picking | No status guard: a FULFILLED job can be fulfilled again (double deduction, duplicate invoice and backorders). | Critical | Confirmed |
| F10 | Picking | Fulfil, stock deduction and invoice run as 3 separate transactions from the controller. | High | Confirmed |
| F11 | SO / shipment / backorder | SO status never moves to `PARTIALLY_SHIPPED` or `SHIPPED`. Backorders are never resolved and can be counted twice. | High | Confirmed |
| F12 | Picking (backorder jobs) | The bin options per row are indexed by SO line, but the form is indexed by picking-job line. | High | Confirmed |
| F13 | SO / PO edit | Editing does not update the pending picking job or GRN. `IndexOutOfBounds` when lines are removed. `Long` compared with `!=`. Edits are allowed after partial receipt or pick. | Medium | Confirmed |
| F14 | SO / PO edit, delete | `removeRow` checks status on the form object, not the database row. `deleteSalesOrder` calls `Optional.get()` on an empty value. | Medium | Confirmed |
| F15 | Put-away | Index-based pairing against a re-queried, non-deterministically ordered list. Negative moves allowed. Destination `.get()` unchecked. | Medium | Confirmed |
| F16 | Stock adjustment | qty not validated (negative). NPE when destination is null. Arbitrary row chosen. Cycle count only overwrites one row. | Medium | Confirmed |
| F17 | Reporting / status codes | Native SQL treats `po.status = 1` as in-transit, but ordinal 1 is `RECEIVED`. | Medium | Confirmed |
| F18 | Concurrency | No optimistic locking on PO, PickingJob or Stock. Concurrent double-submit is not prevented. | Low | Confirmed |
| F19 | Invoice | An empty invoice is created when nothing was picked. Debug `System.out`. | Low | Confirmed |
| F20 | Dead code | `GoodsReceiptNoteImplBack.java` is fully commented out and stale. | Info | Confirmed |

---

## F1 — GRN fulfilment always marks the PO `RECEIVED` (Critical)

**File / method:** `Services/GoodsReceiptNoteImpl.java`, `fulfill(GoodsReceiptNote, GoodsReceiptNoteDto)`, lines 104–138.

```java
120            if (qty == 0) continue;
...
128            receivedPurchaseOrderLines++;
129        }
130        if (receivedPurchaseOrderLines == 0) return null;
131
132        else if (receivedPurchaseOrderLines < purchaseOrderLines) {
133            purchaseOrder.setStatus(PoStatus.PARTIALLY_RECEIVED);
134        }
135        purchaseOrder.setStatus(PoStatus.RECEIVED);
136        goodsReceiptNote.setStatus(GrnStatus.FULFILLED);
```

**Actual behaviour.**
1. Line 135 runs unconditionally, because there is no `else`. Any receipt with at least one non-zero line leaves the PO `RECEIVED`. `PARTIALLY_RECEIVED` is assigned and then overwritten straight away.
2. "Partial" only counts *lines with qty > 0*. It never compares the received quantity with the ordered quantity. Receiving 1 of 10 units on every line counts as complete.
3. Nothing is created for the outstanding quantity: no new PENDING GRN and no remaining-qty tracking. The current GRN goes to `FULFILLED`.
4. The PO is then locked by the `RECEIVED` guards (`PurchaseOrderServiceImpl.updateById` line 171, controller lines 161/178). The rest of the delivery can never be booked in.

**Expected.** For each PO line, compare the cumulative received qty (across all GRNs of the PO) with `PurchaseOrderLine.qty`:
* All lines complete → PO `RECEIVED`, GRN `FULFILLED`.
* Some quantity received but not all → PO `PARTIALLY_RECEIVED`, current GRN `FULFILLED`. The outstanding quantity must stay receivable, either through a new PENDING GRN for the remaining qty or by allowing another receipt against the PO.
* Nothing received → PO and GRN unchanged, and the caller is told so (see F2).

**Recommended correction.** Compute outstanding qty per PO line. Received qty = sum of `GoodsReceiptNoteLine.qty` over FULFILLED GRNs of that PO for the item/line. Decide the status from that, and replace lines 132–135 with a proper if/else. Reject `qty > outstanding` (see F4). The commented-out `GoodsReceiptNoteImplBack` (F20) shows the original design intent: a new PENDING GRN holding the unreceived lines. That design fits the existing schema with no migration, because `goods_receipt_note` / `goods_receipt_note_line` already exist. Put the status decision in the domain if possible, for example a `PurchaseOrder.applyReceipt(...)` or `PurchaseOrder#recalculateStatus(receivedByLine)` method. That keeps the rule encapsulated and unit-testable.

**Required tests (service level, `@DataJpaTest` or `@SpringBootTest` with the H2 Flyway schema):**
* Full receipt of every line → PO `RECEIVED`, GRN `FULFILLED`, floor stock += qty for each item.
* One line received in full, one line qty 0 → PO `PARTIALLY_RECEIVED`.
* Every line received short (e.g. 1 of 5) → PO `PARTIALLY_RECEIVED`, never `RECEIVED`.
* Two receipts that together complete the PO → after the second, PO `RECEIVED`.
* All-zero receipt → PO stays `IN_TRANSIT`, no stock row created, GRN stays `PENDING`.

---

## F2 — Partially received POs are a dead end; empty receipts reported as success (High)

**File / method:** `Controllers/GoodsReceiptNoteController.java`, `startReceivingShipment` (lines 139–166) and `finishReceivingShipment` (lines 168–183).

```java
144        if (purchaseOrder.isPresent() && purchaseOrder.get().getStatus() != PoStatus.RECEIVED) {   // GET
...
174        if (purchaseOrder.isPresent() && purchaseOrder.get().getStatus() == PoStatus.IN_TRANSIT) { // POST
175            Optional<GoodsReceiptNote> goodsReceiptNote = goodsReceiptNoteService.findByPurchaseOrder(purchaseOrder.get().getId());
176            goodsReceiptNoteService.fulfill(goodsReceiptNote.get(), goodsReceiptNoteDto);
177            return "redirect:/goods-receipt-notes/search-purchase-order?received";
```

**Actual.**
* GET accepts `PARTIALLY_RECEIVED` and renders the form. POST only accepts `IN_TRANSIT`, so a `PARTIALLY_RECEIVED` PO falls through to `?notFound` (line 181). This is a misleading message, and once F1 is fixed it becomes a real dead end.
* The return value of `fulfill` is ignored. When it returns `null` (nothing received, line 130), the user still sees `?received`.
* `goodsReceiptNote.get()` has no presence check. A missing GRN throws `NoSuchElementException`, which becomes a 500.

**Expected.** POST accepts `IN_TRANSIT` and `PARTIALLY_RECEIVED`. An empty receipt redirects with a distinct flag such as `?nothingReceived`. A missing GRN is turned into a handled exception that `GlobalExceptionHandler` maps, for example a new `GoodsReceiptNoteNotFoundException` that follows the existing `*NotFoundException` pattern.

**Required tests (MockMvc on the controller with the service mocked, plus a service-level test):**
* POST for a `PARTIALLY_RECEIVED` PO is processed.
* POST with all-zero quantities → `?nothingReceived`, and the PO status is unchanged.
* POST for a `RECEIVED` PO → `?alreadyReceived`.

---

## F3 — Multiple GRNs per PO; `findByPurchaseOrder` returns a single `Optional` (High)

**Files:**
* `Services/PurchaseOrderServiceImpl.java`, `save()` lines 212–235. It creates a PENDING GRN whose lines are pre-filled with the *ordered* qty:
  ```java
  217        GoodsReceiptNote goodsReceiptNote = GoodsReceiptNote.builder().date(po.getDate())
  218                .purchaseOrder(po).date(po.getDate()).status(GrnStatus.PENDING).build();
  ...
  226                    .qty(pol.getQty()).item(pol.getItem()).build();
  ...
  230            po.getGoodsReceiptNotes().add(savedGoodsReceiptNote);   // inside the per-line loop → same GRN added N times
  ```
* `Controllers/GoodsReceiptNoteController.java` line 149. **Every GET** of `/{purchaseOrderId}/receive` calls `goodsReceiptNoteService.create(purchaseOrder.get())`, which persists another GRN with qty 0 lines (`GoodsReceiptNoteImpl.create`, lines 205–222).
* `Repositories/GoodsReceiptNoteRepository.java` lines 20–32. A native query is declared as `Optional<GoodsReceiptNote> findByPurchaseOrder(Long)`.

**Actual.** A PO created through the UI has one GRN from `save()`. Opening the receive form adds a second, and every reload adds another. On POST, `findByPurchaseOrder` returns more than one row. Spring Data then throws `IncorrectResultSizeDataAccessException`, and receiving a UI-created PO fails. Bootstrap POs that are `IN_TRANSIT` get no GRN in `save()` (Bootstrap builds POs directly, lines 214–260), so they only hit this after a second GET. The orphan PENDING GRNs also inflate `countPending()`, the dashboard KPI. A GET with side effects also breaks HTTP semantics: prefetch or reload creates data.

**Plausible extra issue.** The native query selects the FK as `AS "purchaseOrder"`, not under the mapped column name `purchase_order_id`. Hibernate 6 entity mapping of native results resolves columns by their mapped names. This alias may therefore fail with "column purchase_order_id not found", even when only one GRN exists. Confirm with a repository test.

**Expected.** At most one *PENDING* GRN per PO at a time. It is created once, when the PO is created or on first receive. GET is idempotent. The lookup is "the pending GRN for PO X" and returns at most one row.

**Recommended correction.**
* Choose a single creation point. Two options:
  * Keep the GRN created in `PurchaseOrderServiceImpl.save`, but with qty 0 lines. Ordered qty belongs on the PO line, not the GRN line.
  * Or make `create()` find-or-create.
* Replace the native query with a derived query such as `Optional<GoodsReceiptNote> findFirstByPurchaseOrderIdAndStatus(Long poId, GrnStatus status)`, or a `List` return.
* Move `po.getGoodsReceiptNotes().add(...)` out of the loop.

None of this changes the schema.

**Required tests:**
* Repository: `findByPurchaseOrder` (or its replacement) with 0, 1 and 2 GRNs for a PO.
* Controller: two consecutive GETs of `/receive` leave exactly one PENDING GRN.
* Service: saving a PO creates exactly one GRN, and `po.getGoodsReceiptNotes().size() == 1`.

---

## F4 — GRN line input is not validated; receipts fragment stock rows (Medium)

**File / method:** `GoodsReceiptNoteImpl.fulfill`, lines 112–127.

```java
112        Optional<WarehouseSection> floor = warehouseSectionRepository.findBySectionNumber(FLOOR_WH_SECTION);
114        for (int i=0; i<goodsReceiptNote.getGoodsReceiptNoteLines().size(); i++) {
115            GoodsReceiptNoteLineDto goodsReceiptNoteLineDto = goodsReceiptNoteDto.getGoodsReceiptNoteLines().get(i);
...
120            if (qty == 0) continue;
125            Stock stock = Stock.builder().qtyOnHand(qty).item(item).warehouseSection(floor.get()).build();
126            stockRepository.save(stock);
```

**Actual.**
* `qty < 0` is accepted and creates a `Stock` row with negative `qty_on_hand`. The only guard is HTML `min="1"`.
* `qty > ordered` (over-receipt) is accepted.
* A DTO with fewer lines than the GRN throws `IndexOutOfBoundsException` (line 115), which becomes a 500.
* A missing floor section throws `NoSuchElementException` from `floor.get()`.
* Every receipt inserts a **new** `Stock` row on the floor, even when one already exists for that item. Multiple rows per (section, item) are therefore normal, and that is what triggers F8.
* Notes on zero-qty lines are dropped (`continue` comes before `setNotes`).

**Expected.** Reject negative values. Reject or flag qty above outstanding. Validate that the DTO size matches. Increment an existing floor `Stock` row for the item rather than inserting a duplicate.

**Recommended correction.** Validate in the service, which is the authoritative place, and throw a domain exception (e.g. `InvalidReceiptException`) mapped in `GlobalExceptionHandler`. Merge into the existing stock row with `findByWarehouseSectionAndItemId`, as `StockAdjustmentServiceImpl.incrementAt` already does. Fail fast with a clear exception when the floor section is missing.

**Required tests:** negative qty is rejected; qty above outstanding is rejected; DTO size mismatch is rejected with a domain exception (not IOOBE); two receipts of the same item leave **one** floor stock row with the summed qty.

---

## F5 — `PickingJobServiceImpl.fulfill` modifies the list it iterates by index (Critical)

**File / method:** `Services/PickingJobServiceImpl.java`, `fulfill(PickingJob, PickingJobDto)`, lines 150–213.

```java
158        for (int i = 0; i < pickingJob.getPickingJobLines().size(); i++) {
159            Long warehouseSectionId = pickingJobDto.getPickingJobDtoLines().get(i).getWareHouseSectionId();
160            Long itemId = pickingJob.getPickingJobLines().get(i).getItem().getId();
161            int qtyPicked = pickingJobDto.getPickingJobDtoLines().get(i).getQtyPicked();
162            PickingJobLine pickingJobLine = pickingJob.getPickingJobLines().get(i);
...
166            if (stock == null || warehouseSectionId == 0L || qtyPicked == 0) {
168                pickingJobDtoLinesForBackOrders.add(pickingJobLine);
169                pickingJob.getPickingJobLines().remove(i);
170                pickingJobLineRepository.delete(pickingJobLine); // since it is a backorder
...
211        pickingJob.setPickingJobLines(savedPickingJobLines);
```

**Actual.** Take a job with lines `[A, B, C]` and DTO rows `[a, b, c]`, where A is out of stock.
1. `i = 0`: A is removed. The entity list becomes `[B, C]`.
2. `i = 1`: the code reads entity line **C** but DTO row **b**, which holds B's bin and qty.
3. The loop ends because `size() == 2`.

The results:
* **B is never processed.** It is not picked and not backordered. Line 211 replaces the collection with `savedPickingJobLines`, and `@OneToMany(cascade = ALL)` has no `orphanRemoval`. B's row therefore stays in `picking_job_line` with `qty_picked = null` and no section, still pointing at the FULFILLED job. Its demand is silently lost.
* **C is processed with B's bin and qty.** Usually the stock lookup for (B's bin, C's item) returns `null`, so C is backordered wrongly. If C happens to be stocked in B's bin, it is picked with B's quantity.
* With two consecutive out-of-stock lines, the second is skipped entirely and never becomes a backorder.

The code also indexes the DTO without checking its size, so a short DTO throws `IndexOutOfBoundsException`. When every line is out of stock, the job is still marked FULFILLED with zero lines.

**Expected.** Each picking-job line is evaluated exactly once, against *its own* DTO row. Each line ends as either picked (fully or partly) or backordered.

**Recommended correction.** Iterate over an index-stable snapshot. For example, run `for (int i = 0; i < lines.size(); i++)` over `List.copyOf(pickingJob.getPickingJobLines())` and pair it with `dto.get(i)`. Better still, carry the `pickingJobLineId` in `PickingJobLineDto` and match by id rather than by position. Collect the removals and apply them after the loop. Add `orphanRemoval = true`, or delete the lines explicitly, so no orphan rows remain. Validate that the DTO size equals the line count.

**Required tests (service, with real repositories on H2):**
* `[out-of-stock, in-stock, in-stock]`: both in-stock lines are picked with *their own* qty and bin, and one backorder is created for the first.
* `[out, out, in]`: two backorders and one pick.
* `[in, out, in]`.
* After fulfilment, no `picking_job_line` row remains attached to the original job without a section or picked qty.
* A DTO with fewer rows than job lines → domain exception.

---

## F6 — Partially picked quantities do not create backorders; over-picking is allowed (High)

**File / method:** `PickingJobServiceImpl.fulfill`, lines 171–181.

```java
173                if (stock.getQtyOnHand() >= qtyPicked) {
174                    pickingJobLine.setQtyPicked(pickingJobDto.getPickingJobDtoLines().get(i).getQtyPicked());
175                } else {
176                    pickingJobLine.setQtyPicked(stock.getQtyOnHand());
177                }
```

**Actual.**
* When the bin holds less than requested, `qtyPicked` is capped at stock, but **the shortfall disappears**. No `Backorder` and no follow-up picking-job line are created for `qtyToPick - qtyPicked`.
* When the picker enters less than `qtyToPick`, for example 2 of 5, the remaining 3 also disappear.
* `qtyPicked > qtyToPick` is accepted, provided stock allows it. The customer is invoiced (`InvoiceServiceImpl` line 47) for more than was ordered.
* Backorders are only created when the whole line is unpicked (line 166).

**Expected.** `picked = min(enteredQty, qtyToPick, availableInBin)` and `remainder = qtyToPick - picked`. If `remainder > 0`, add a backorder picking-job line with `qtyToPick = remainder` and a `Backorder(qty = remainder)`. Entering more than `qtyToPick` is rejected.

**Recommended correction.** Merge the "fully unpicked" branch and the "partially picked" branch into one remainder computation. Put the rule on the domain object, for example `PickingJobLine.pick(int requested, int available)` returning the remainder. This gives encapsulation and a unit-testable rule. Create backorder lines from the remainder list. No schema change is needed: `Backorder.qty` and `PickingJobLine.qtyToPick` already exist.

**Required tests:**
* Stock 3, `qtyToPick` 5, entered 5 → picked 3, backorder 2, invoice line 3.
* Stock 10, `qtyToPick` 5, entered 2 → picked 2, backorder 3.
* Entered 6 when `qtyToPick` is 5 → rejected.
* Full pick → no backorder.

---

## F7 — Negative `qtyPicked` passes the check and increases stock (Medium)

**Files:** `PickingJobServiceImpl.fulfill` lines 166 and 173, and `StockServiceImpl.pickStock` line 57.

```java
166            if (stock == null || warehouseSectionId == 0L || qtyPicked == 0) {
173                if (stock.getQtyOnHand() >= qtyPicked) {           // true for any negative value
 57            int remainingBalance = stock.getQtyOnHand() - pickingJobLine.getQtyPicked(); // qoh - (-n) = qoh + n
```

**Actual.** A crafted POST with `qtyPicked=-5` stores `qtyPicked = -5`, and `pickStock` then *raises* `qty_on_hand` by 5. The invoice skips the line (`qtyPicked > 0`, Invoice line 46), so stock is created out of nothing with no audit trail. The only protection is HTML `min="1"`.

**Expected.** `qtyPicked` must satisfy `0 <= qtyPicked <= qtyToPick`. Anything outside that range is rejected server-side.

**Recommended correction.** Add server-side validation in the service, which is authoritative even if bean-validation annotations are also added to `PickingJobLineDto`. Throw a domain exception mapped in `GlobalExceptionHandler`.

**Required tests:** a negative `qtyPicked` is rejected and stock is unchanged.

---

## F8 — `pickStock` reads one stock row but updates or deletes all rows for (section, item) (Critical)

**Files:** `Services/StockServiceImpl.java`, `pickStock`, lines 43–66, and `Repositories/StockRepository.java` lines 358–396.

```sql
-- findByWarehouseSectionAndItemId (358-370)
SELECT TOP 1 * FROM STOCK s WHERE s.item_id = :itemId AND s.warehouse_section_id = :warehouseSectionId
ORDER by s.qty_on_hand DESC
-- updateStockByWarehouseSectionAndItemId (372-384)
UPDATE STOCK s SET s.qty_on_hand = :newQtyOnHand WHERE s.warehouse_section_id = :warehouseSectionId AND s.item_id = :itemId
-- deleteStockByWarehouseSectionAndItemId (386-396)
DELETE STOCK s WHERE s.warehouse_section_id = :warehouseSectionId AND s.item_id = :itemId
```
```java
57            int remainingBalance = stock.getQtyOnHand() - pickingJobLine.getQtyPicked();
60            if (remainingBalance > 0) {
61                stockRepository.updateStockByWarehouseSectionAndItemId(remainingBalance, wareHouseSectionId, itemId);
62            } else {
63                stockRepository.deleteStockByWarehouseSectionAndItemId(wareHouseSectionId, itemId);
```

**Actual.** Several `Stock` rows per (section, item) are normal. Every GRN inserts a new floor row (F4). A partial put-away inserts a new destination row and never merges (F15). Bootstrap may seed duplicates. Given rows of 10 and 5 in the same bin:
* Picking 3 → `remainingBalance = 7`, and the `UPDATE` sets **both** rows to 7. On-hand becomes 14 instead of 12, so stock is created out of nothing.
* Picking 10 → `remainingBalance = 0`, and the `DELETE` removes **both** rows. Five units are lost.

The availability check in `fulfill` (F6) also compares against the largest single row, not the bin total. Two lines of the same item in the same bin are handled by re-reading TOP 1, so the effect compounds.

**Expected.** Deduct exactly `qtyPicked` from the bin's total for that item. Never touch more units than were picked.

**Recommended correction.** Operate on the `Stock` entity by id: load it, `setQtyOnHand(q - picked)`, then save or delete *that row*. Or take the rows for (section, item) and consume them in order until `picked` is satisfied. Better still, enforce one row per (section, item): merge on insert in GRN, put-away and adjustments. A unique constraint would need a new Flyway migration (`V3__...`) and a cleanup of existing duplicates. That is a justified schema change, but it can come after the service-level fix. Retire the bulk `UPDATE ... WHERE section AND item` and `DELETE ... WHERE section AND item` queries, or restrict them to a stock id.

**Required tests (Repository/Service, H2):**
* Two rows (10, 5) in the same bin. Pick 3 → total 12. Pick 10 more → total 2, so rows are not over-deleted.
* A single row with a full pick → the row is deleted and the total is 0.
* Two picking lines for the same item in the same bin → the total is deducted by the sum.

---

## F9 — Picking jobs can be fulfilled repeatedly (Critical)

**Files:** `Controllers/PickingJobController.java`, `startPickingProcess` (lines 109–136) and `fulfillPickingJob` (lines 147–167). Also `PickingJobServiceImpl.fulfill` (line 152).

```java
154        if (pickingJob.isPresent()) {
156            PickingJob updatedPickingJob = pickingJobService.fulfill(pickingJob.get(), pickingJobDto);
158            stockService.pickStock(updatedPickingJob);
160            invoiceService.createByPickingJob(pickingJob.get());
```

**Actual.** Neither the controller nor the service checks `pickingJob.getStatus() == PENDING`. A browser refresh/resubmit, a double-click or a replayed POST on a FULFILLED job will:
* deduct stock again (`pickStock`);
* create a second invoice;
* if any line falls to the backorder path, create another backorder picking job and more `Backorder` rows.

The GET form for a FULFILLED job is also served. GRNs are partly protected, because the POST requires the PO to be `IN_TRANSIT` (F2). But there is no equivalent check on GRN status, and with duplicate GRNs (F3) the protection relies only on the PO state.

**Expected.** Fulfilment is idempotent. Fulfilling a non-PENDING job is rejected with a handled error, and nothing changes.

**Recommended correction.** Add a guard at the start of `PickingJobServiceImpl.fulfill`:
```java
if (pickingJob.getStatus() != PjStatus.PENDING) throw new PickingJobAlreadyFulfilledException();
```
This is a new domain exception, mapped in `GlobalExceptionHandler` to `redirect:/picking-jobs?alreadyFulfilled`. Have the GET redirect for non-PENDING jobs. Add the same guard on GRN status in `GoodsReceiptNoteImpl.fulfill`. Better still, model transitions as an explicit method, `PickingJob.markFulfilled()`, which throws on an illegal transition. That gives encapsulation and a small state-machine pattern.

**Required tests:**
* Service: a second `fulfill` on the same job throws, and stock, invoices and backorder counts are unchanged.
* MockMvc: a POST to a FULFILLED job redirects with `?alreadyFulfilled`.
* GRN: a second `fulfill` on a FULFILLED GRN throws.

---

## F10 — Fulfilment is not atomic (High)

**File / method:** `PickingJobController.fulfillPickingJob`, lines 156–160 (quoted in F9).

**Actual.** `fulfill` is `@Transactional` and commits on return. `StockServiceImpl.pickStock` has no `@Transactional`; each `@Modifying` repository query runs in its own transaction (`StockRepository` lines 372–396). `InvoiceServiceImpl.createByPickingJob` is not transactional either. If a stock update throws, for example because `pickingJobLine.getWarehouseSection()` is null for a skipped line (F5), the job is already FULFILLED. Stock is then partly deducted and no invoice exists. Because of F9, a retry deducts again.

**Expected.** Fulfil, deduct and invoice either all succeed or all roll back.

**Recommended correction.** Move the orchestration into one service method, `PickingJobService.fulfill(...)` or a new `OrderFulfilmentService.fulfillPickingJob(...)`, annotated `@Transactional`. It calls the stock and invoice services. The controller then becomes a thin adapter, in line with the Single Responsibility Principle (SRP) and a Facade.

**Required tests:** force `pickStock` to fail, for example with a mocked `StockService` throwing inside a `@SpringBootTest`. The picking job then stays PENDING and no invoice exists.

---

## F11 — SO status never moves to shipped; backorders are never resolved (High)

**Evidence.** I searched for `setStatus` and `SoStatus.` in `src/main/java`. The only runtime writer of SO status is `SalesOrderServiceImpl.updateById` lines 153–155:
```java
153            if (existing.getBackorders().size() == 0 && existing.getStatus() == SoStatus.PARTIALLY_SHIPPED) {
154                existing.setStatus(SoStatus.SHIPPED);
```
It depends on `PARTIALLY_SHIPPED`, which nothing ever sets. `Bootstrap` is the only other writer (lines 376, 403–416). `PickingJobServiceImpl.fulfill` and `InvoiceServiceImpl` never update the SO.

**Actual.**
* After picking and invoicing, the SO stays `PENDING` forever. Shipped orders remain editable and deletable. The `SHIPPED` guards in `SalesOrderController` lines 272/323 and `SalesOrderServiceImpl` line 149 never fire for real orders.
* "Pending to ship" reports and KPIs (`SalesOrderRepository` `so.status = 0` queries, e.g. lines 97, 146, 195) include orders that are already fulfilled.
* `Backorder` rows are never deleted or marked resolved. No caller of `BackorderService.delete` exists outside the service. When the backorder picking job is fulfilled, the original `Backorder` stays. If that job is itself short, `fulfill` creates a *second* `Backorder` for the same demand (line 199). The backorder report and KPI double-count.
* There is no shipment or delivery entity. "Shipment" is implied by the picking job plus the invoice. That is acceptable for scope, but the status lifecycle has to stand in for shipment tracking.

**Expected.** After a picking job is fulfilled:
* every SO line fully picked across all jobs, and no open backorders → `SHIPPED`;
* some quantity picked with backorders open → `PARTIALLY_SHIPPED`.

When a backorder picking job picks an item, the matching `Backorder` qty is reduced or deleted.

**Recommended correction.** Link a backorder picking-job line to its `Backorder`, or reduce `Backorder` rows by (salesOrder, item) when the backorder job is fulfilled. Recompute SO status at the end of the transactional fulfil (F10). A `SalesOrder.recalculateStatus()` domain method keeps the rule encapsulated. The current enum values suffice, so no schema change is needed.

**Required tests:**
* Full pick → SO `SHIPPED`, with zero backorders.
* Partial pick → SO `PARTIALLY_SHIPPED`, with one backorder.
* Fulfilling the backorder job → backorder removed and SO `SHIPPED`.
* A short backorder-job fulfilment does not create a duplicate backorder for the same units.

---

## F12 — Bin options for backorder picking jobs are misaligned (High)

**Files:** `Services/WarehouseSectionServiceImpl.java` lines 24–37, `PickingJobController` lines 91–92 and 126–127, and `templates/pickingJobs/fulfillPickingJobForm.html` lines 34–45.

```java
24    public List<List<WarehouseSectionDto>> findBySalesOrder(SalesOrder salesOrder) {
26        for (SalesOrderLine salesOrderLine : salesOrder.getSaleOrderLines())      // one entry per SO line
27            warehouseSectionList.add(warehouseSectionRepository.findByItemId(salesOrderLine.getItem().getId()));
```
```html
34  <tr th:each="pickingJobLine, pjStat : ${pickingJob.pickingJobLines}">          <!-- indexed per PICKING JOB line -->
45      <option th:each="warehouseSection: ${warehouseSections[__${pjStat.index}__]}"
```

**Actual.** For an original job, picking-job lines line up 1:1 with SO lines, so it works. A backorder job contains only a subset, for example SO line 3 alone. Row 0 is then offered the bins of **SO line 0's item**. The picker chooses a bin that does not hold the item, the stock lookup returns `null`, and the line is backordered again, which loops. If the other item happens to be in that bin, the wrong stock is touched through F8. The same misalignment affects `findWarehouseSectionSuggestionBySalesOrder` on the details page. It also misaligns when SO lines were edited after the job was created (F13).

**Expected.** The bin options are derived from each picking-job line's own item.

**Recommended correction.** Add `findByPickingJob(PickingJob)`, which iterates `pickingJob.getPickingJobLines()`. Use it in both controller methods.

**Required tests:** a unit test where a picking job holds only the second SO line. The options list at index 0 contains only bins for that line's item.

---

## F13 — SO/PO edits do not update pending picking jobs or GRNs (Medium)

**Files:** `SalesOrderServiceImpl.updateById` lines 143–193 and `PurchaseOrderServiceImpl.updateById` lines 164–206.

```java
164                Long modifiedSolItemId = salesOrder.getSaleOrderLines().get(i).getItem().getId();   // IOOBE if fewer lines posted
168                if (existingSolItemId != modifiedSolItemId) {                                    // Long reference comparison
```

**Actual.**
* Editing SO line qty or item leaves the pending `PickingJobLine.qtyToPick` and item unchanged. The warehouse picks the *old* order, and lines added to the SO are never picked. The same applies to PO edits and the pending GRN created in `save()`. `GoodsReceiptNoteImpl.fulfill` indexes the GRN lines against the DTO, and the form displays `purchaseOrder.purchaseOrderLines[i].qty` (`fulfillGoodsReceiptNoteForm.html` line 38). After a PO edit, the GRN and PO line indexes no longer correspond.
* If fewer lines are posted than exist, `get(i)` throws `IndexOutOfBoundsException`, which becomes a 500.
* `Long != Long` compares references. For ids above 127 it is always `true`. That is harmless here, because it only re-sets the same item, but it is a correctness smell. Use `Objects.equals`.
* Edits are allowed when the PO is `PARTIALLY_RECEIVED`, or when the SO's picking job is already FULFILLED (because of F11 the SO stays PENDING).

**Expected.** An SO or PO can only be edited while nothing has been picked or received. Alternatively, edits are propagated to the pending picking job or GRN.

**Recommended correction.** Restrict edits to `IN_TRANSIT` POs with no FULFILLED GRN, and to `PENDING` SOs with no FULFILLED picking job. Throw the existing `ReceivedOrderModificationException` or `ShippedOrderModificationException`. Otherwise, propagate line changes to the PENDING picking job or GRN. Use `Objects.equals`, and guard against size mismatches.

**Required tests:** edit an SO qty → the pending picking job's `qtyToPick` matches, or the edit is rejected. Editing a PARTIALLY_RECEIVED PO → `ReceivedOrderModificationException`. Posting fewer lines → no 500.

---

## F14 — `removeRow` checks status on the form object; `deleteSalesOrder` NPE path (Medium)

**Files:** `Controllers/PurchaseOrderController.java` lines 264–270, `Controllers/SalesOrderController.java` lines 268–274 and 319–330.

```java
266        PurchaseOrder order = purchaseOrderService.findById(id).get();
268        if (purchaseOrder.getStatus() == PoStatus.RECEIVED) {   // purchaseOrder = @ModelAttribute from the form
```
```java
272        if (salesOrder.getStatus() == SoStatus.SHIPPED) {       // salesOrder = @ModelAttribute from the form
```
```java
323        if (order.isPresent() && order.get().getStatus() != SoStatus.SHIPPED) {
326        } else if (order.get().getStatus() == SoStatus.SHIPPED) {   // order may be empty → NoSuchElementException
```

**Actual.**
* The status checked comes from the request-bound object. A new `PurchaseOrder` defaults to `IN_TRANSIT` and a new `SalesOrder` to `PENDING`. Unless the form posts a status, **lines of a RECEIVED PO or SHIPPED SO can be deleted** through `removeRow`. That rewrites history for stock already received or shipped.
* `findById(id).get()` has no presence check.
* `deleteSalesOrder` on a non-existent id throws `NoSuchElementException` instead of reaching the `notFound` branch.
* `deletePurchaseOrder` allows deleting a `PARTIALLY_RECEIVED` PO, even though stock was already booked against it.

**Expected.** Check the *persisted* status. A missing entity leads to the existing `*NotFoundException`, which `GlobalExceptionHandler` maps. Deletion is blocked once any receipt or pick exists.

**Required tests (MockMvc):**
* `removeRow` on a RECEIVED PO → `cannotBeUpdated`, and the line still exists.
* `removeRow` on a SHIPPED SO → `cannotBeUpdated`.
* Deleting a non-existent SO id → redirect `?notFound`, not 500.
* Deleting a PARTIALLY_RECEIVED PO → `?failedToDelete`.

---

## F15 — Put-away pairs rows by index against a re-queried list (Medium)

**Files:** `StockServiceImpl.putAwayStocks` lines 96–126, `generatePutAwayTaskDtoByWarehouseSection` lines 73–84, `PutAwayController` lines 30–51 and 82–100, `StockRepository.findStockOnFloor` and `findStocksByWarehouseSectionNumber` (lines 398–431, `ORDER BY s.qty_on_hand DESC`), and `Domains/DTOs/PutAwayTaskDto.java`.

```java
 99        for (int i=0; i<wrapper.getPutAwayTasks().size(); i++) {
101            Stock stock = stocks.get(i);                       // pairing by position with a list re-queried on POST
105            Optional<Stock> movedStock = stockRepository.findById(stock.getId());
107                Optional<WarehouseSection> destination = warehouseSectionRepository.findById(destinationWarehouseSectionId);
109                if (qtyMoved >= movedStock.get().getQtyOnHand()) {
111                    movedStock.get().setWarehouseSection(destination.get());   // unchecked .get()
...
115                    Stock newStock = Stock.builder()...qtyOnHand(qtyMoved)...  // negative qtyMoved → negative row
120                    movedStock.get().setQtyOnHand(movedStock.get().getQtyOnHand() - qtyMoved);
```

**Actual.**
* The GET renders rows from `findStocksOnFloor()`. The POST re-runs the same query and pairs by index. `ORDER BY qty_on_hand DESC` has no tiebreaker, so equal quantities can come back in a different order. A GRN received between the GET and the POST also shifts the indexes. Either way, **the wrong stock row is moved**. A longer wrapper throws `IndexOutOfBoundsException`.
* The wrapper size comes from `warehouseSection.getStocks()` (an entity collection, unordered), but the rows come from the query. The counts normally match; the ordering source does not.
* `PutAwayTaskDto` has no `stockId` field. The template renders `putAwayTasks[i].stockId` inside a `th:text` cell, so it is replaced and never posted. Row identity is therefore never sent back.
* A negative `qtyToMove` goes to the `else` branch. It creates a negative-qty row at the destination and *increases* the source. The only guard is HTML `min="1"`.
* `destination.get()` with the default `destinationWarehouseSectionId = 0L` throws `NoSuchElementException`, which becomes a 500.
* A full move re-parents the row and does not merge into an existing destination row. A partial move inserts a new row. Both create the duplicate rows that F8 corrupts.

**Expected.** Each task identifies its stock row by id. Quantities are validated (`1 <= qty <= qtyOnHand`). The destination exists and is different from the source. Moved quantity is merged into the destination row.

**Recommended correction.** Add `Long stockId` to `PutAwayTaskDto` (a DTO, so no schema impact) and render it as a hidden input. Look up by id in `putAwayStocks`, and drop the `stocks` list parameter. Validate, and throw a domain exception. Merge with `findByWarehouseSectionAndItemId`, the same way as `incrementAt`.

**Required tests:**
* Two floor rows with equal qty, where the tasks target a specific `stockId` → the correct row moves.
* Partial move → source reduced, destination row created or merged, total unchanged.
* Negative qty → rejected.
* Destination 0 or missing → handled error.

---

## F16 — Stock adjustments do not validate qty, can NPE, and touch an arbitrary row (Medium)

**Files:** `Services/StockAdjustmentServiceImpl.java` lines 18–84 and `Controllers/StockAdjustmentController.java` lines 43–67. `Domains/StockAdjustment.java` line 49–50 has `private int qty;` with no validation.

```java
22            case CYCLE_COUNT -> setQtyAt(adjustment, adjustment.getSource(), adjustment.getQty());
26                incrementAt(adjustment, adjustment.getDestination(), adjustment.getQty());   // destination may be null
41        Stock stock = stockRepository.findByWarehouseSectionAndItemId(section.getId(), ...);   // TOP 1 only
54        Stock here = stocks.stream().filter(... section ...).findFirst()                   // arbitrary row
```
```java
63        } catch (IllegalStateException e) {        // controller only catches ISE
```

**Actual.**
* A negative `qty` is accepted:
  * `WRITE_OFF -5` *adds* 5, because `remaining = qoh + 5`.
  * `CYCLE_COUNT -3` sets a negative on-hand.
  * A negative `TRANSFER` moves stock in reverse.
* `DAMAGE` or `TRANSFER` without a destination (allowed: the controller passes `null`, and the column is nullable in V2) → `NullPointerException` in `incrementAt`. The controller does not catch it, so it becomes a 500, after `decrementAt` has already run inside the same transaction. The transaction rolls back, but the user gets an error page.
* `TRANSFER` with source == destination is allowed: an audit row for no movement.
* With duplicate rows (F4/F15), `decrementAt` checks only the first row found. It can refuse ("below zero") even when the bin total is enough. `CYCLE_COUNT` overwrites only the largest row, so the bin total ≠ the counted qty.
* Business errors are signalled with `IllegalStateException`, not a domain exception in `Domains/Exceptions` handled by `GlobalExceptionHandler`. This is inconsistent with the project's conventions.

**Expected.** `qty >= 0` for CYCLE_COUNT and `qty > 0` for the other types. DAMAGE and TRANSFER require a destination different from the source. Operations act on the bin total. Errors are domain exceptions.

**Recommended correction.** Validate in `apply()` (and optionally with `@Min` on the entity and form). Add an `InvalidStockAdjustmentException`, or an `InsufficientStockException`, mapped to `redirect:/stock-adjustments/new?failed`. Operate on the aggregated rows, or on the single merged row once F8's one-row-per-(section, item) rule exists.

**Required tests:** WRITE_OFF with negative qty → rejected and stock unchanged. TRANSFER without a destination → handled error with no partial change. CYCLE_COUNT with duplicate rows → bin total equals the counted qty. TRANSFER source == destination → rejected.

---

## F17 — Native SQL status codes contradict the enum ordinals (Medium)

**Files:** `Domains/PurchaseOrder.java` lines 60–63 (`IN_TRANSIT(0), RECEIVED(1), PARTIALLY_RECEIVED(2)`). The enum has no `@Enumerated`, so the database stores the **ordinal** (V1 `status tinyint check (status between 0 and 2)`). `Repositories/StockRepository.java` lines 208 and 218, in `findStockReportsItemsByVendorId`:

```sql
200            -- QTY IN TRANSIT NESTED QUERY
...
208             AND po.status = 1          -- 1 = RECEIVED, not IN_TRANSIT
```
The commented code at line 284 even states `po.status = 1 (IN-TRANSIT)`.

**Actual.** The "Qty In Transit" and "In-Transit WOI" columns of the stock-level-by-vendor report add up **received** POs, not in-transit ones. Other queries use the correct codes: `IN (0, 2)` in `findReorderCandidates` (line 602) and `PurchaseOrderRepository` lines 125/178. The `SoStatus` comments `//2`, `//3` (`SalesOrder.java`) do not match the ordinals 1 and 2, although the SQL there uses 0 and 1 correctly. `PoStatus.pk` is never used for persistence. Reordering the enum constants would silently remap stored data.

**Expected.** In-transit is `po.status IN (0, 2)`, meaning not yet fully received, or `= 0` if partial receipts are counted separately.

**Recommended correction.** Fix lines 208 and 218, and correct the misleading comments. Optionally, pin persistence to `pk` with an `AttributeConverter`. That needs no schema change, because the values are identical. It decouples storage from declaration order.

**Required tests:** in `StockRepositoryTest`, one IN_TRANSIT PO and one RECEIVED PO for the same item → `qtyInTransit` equals only the IN_TRANSIT qty.

---

## F18 — No concurrency protection (Low)

**Files:** `PurchaseOrder`, `GoodsReceiptNote`, `PickingJob` and `Stock` have no `@Version` field.

**Actual.** Two concurrent submissions of the same receipt or pick both pass the status check before either commits, so the work is processed twice. Two concurrent picks from the same bin perform lost-update arithmetic. This is low risk in a single-user demo, but it is a real correctness gap.

**Recommended correction.** Fixing F9 removes the sequential double-submit. For true concurrency, add `@Version` (this needs a Flyway `V3__add_version_columns.sql`, which is a justified schema change), or use a `@Lock(PESSIMISTIC_WRITE)` finder for the job, GRN and stock being modified.

**Required tests:** optional, as a concurrency test with two threads and a latch. At minimum, document the limitation.

---

## F19 — Empty invoices; debug output (Low)

**File / method:** `Services/InvoiceServiceImpl.java`, `createByPickingJob`, lines 40–53.

```java
42        Invoice savedInvoice = invoiceRepository.save(invoice);        // saved before knowing if anything was picked
45            System.out.printf("PickingJobLine qtyPicked: %d\n", pickingJobLine.getQtyPicked());
```

**Actual.** When every line is backordered, an invoice with zero lines is persisted for the customer. `System.out` is used instead of a logger.

**Recommended correction.** Skip invoice creation when no line has `qtyPicked > 0`, returning an `Optional` or `null`. Use SLF4J.

**Required tests:** fulfilling a job with every line out of stock → no invoice created.

---

## F20 — `GoodsReceiptNoteImplBack.java` is dead code (Info)

**File:** `Services/GoodsReceiptNoteImplBack.java` (153 lines; last touched in commit `b7e28b2`, "Refactoring Receiving Shipment Logic").

**Facts.**
* Every line is a `//` comment. The file compiles to nothing, so there is no class and no bean. It is dead code.
* It is stale against the current API:
  * `int fulfill(...)` versus the current `GoodsReceiptNote fulfill(...)`;
  * `findByPurchaseOrder(int)` versus `Long`;
  * `findAllPending()`, which is not in the interface;
  * `GrnStatus.RECEIVED`, which does not exist (the enum is `PENDING`, `FULFILLED`);
  * `GoodsReceiptNoteLineDto.getDamaged()`, which does not exist.
* Uncommenting it would fail to compile. Once fixed, it would create a second `@Service` implementing `GoodsReceiptNoteService`, and constructor injection would fail with `NoUniqueBeanDefinitionException`.
* It does document the intended partial-receipt design: a new PENDING GRN for the unreceived lines (its lines 101–146), and a damaged-goods section `11-11-1-1`. That is the behaviour F1 is missing.

**Recommendation.** Delete the file. Git history keeps it. Carry its partial-receipt idea into the F1 fix, and mention it in the design notes.

---

## Recommended fix order

The order follows dependencies, so each step can be covered by tests before the next one.

1. **F8 + F4 merge-on-insert.** Make every stock mutation act on a specific row or the bin total. This is the foundation for every other inventory test.
2. **F5 + F6 + F7 + F9 + F10.** Rewrite `PickingJobServiceImpl.fulfill` as one transactional, idempotent orchestration, with a stable iteration, remainder-based backorders and validation.
3. **F11 + F12.** Add the SO status lifecycle and backorder resolution, and fix the bin options for backorder jobs.
4. **F1 + F2 + F3.** Add quantity-based partial receipt, a single pending GRN per PO, and the controller status handling.
5. **F14, F13, F15, F16.** Use persisted-state guards and validate input, using domain exceptions mapped in `GlobalExceptionHandler`.
6. **F17, F19, F20.** Fix the report SQL, then clean up. **F18** is optional and needs a Flyway `V3` migration.

Schema impact: none of F1–F17, F19 or F20 strictly needs a migration. These would need a new `V3__*.sql`, and existing migrations must never be edited:
* an optional unique (section, item) constraint on `stock` (after merging duplicates);
* `@Version` columns (F18);
* a FK linking a backorder picking-job line to its `Backorder` (an option under F11).

New exceptions should follow the existing `Domains/Exceptions/*Exception` and `GlobalExceptionHandler` redirect convention. Suggested names: `PickingJobAlreadyFulfilledException`, `GoodsReceiptNoteNotFoundException`, `InvalidReceiptException`, `InvalidPickException`, `InvalidStockAdjustmentException`/`InsufficientStockException`.
