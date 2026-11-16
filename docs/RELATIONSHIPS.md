# Relationships: association, aggregation, composition

How the entities relate and what happens to the "part" when the "whole" is saved or deleted.
Rule used: **composition** means the part cannot exist without the whole, so the JPA mapping uses
`cascade = ALL` and `orphanRemoval = true`. **Aggregation** means the part has a life of its own, so there is
no remove cascade. **Association** means two independent objects that just refer to each other.
The mappings are JPA-only: no schema change and no Flyway migration were needed.

## Composition (whole owns its parts)

| Whole | Part | Mapping | Why |
|---|---|---|---|
| PurchaseOrder | PurchaseOrderLine | `cascade = ALL, orphanRemoval = true` | A line has no meaning outside its order. Removing it from the order deletes it. |
| SalesOrder | SalesOrderLine | `cascade = ALL, orphanRemoval = true` | Same as above. |
| GoodsReceiptNote | GoodsReceiptNoteLine | `cascade = ALL, orphanRemoval = true` | A receipt line only exists inside its receipt. |
| Invoice | InvoiceLine | `cascade = ALL, orphanRemoval = true` | Same. |
| PickingJob | PickingJobLine | `cascade = ALL` (no orphan removal) | Also a composition. Orphan removal is left off on purpose: `PickingJobServiceImpl` replaces the whole line list when a job is fulfilled, which would conflict with orphan removal. |
| SalesOrder | Backorder | `cascade = ALL` | A backorder is the unshipped remainder of one order. |
| Item | ItemPrice, ItemCost | `cascade = ALL` | Price and cost history belong to the item. No orphan removal: order lines still point at old prices and costs. |
| Warehouse | WarehouseSection | `cascade = ALL` | Sections belong to a warehouse. |

Both sides of every order/line association are kept in sync by `addLine(...)` / `removeLine(...)`,
so callers never set the back-reference by hand.

## Aggregation (whole groups independent parts)

| Whole | Part | Mapping | Why |
|---|---|---|---|
| Vendor | Item | `cascade = {PERSIST, MERGE}` | An item is a catalogue entry referenced by many documents. Deleting a vendor must never delete its items. The vendor delete guard also refuses when items exist. |
| WarehouseSection | Stock | no cascade | Stock rows are inventory facts that are moved between sections. They are not deleted with a section. |

## Association (independent objects that refer to each other)

| From | To | Mapping | Why |
|---|---|---|---|
| Vendor | PurchaseOrder | `cascade = {PERSIST, MERGE}` | An order is a business record that must outlive changes to the vendor record. Vendor delete is refused while orders exist. |
| Customer | SalesOrder, Invoice | `cascade = {PERSIST, MERGE}` | Same for the customer. Customer delete is refused while orders or invoices exist (it used to only check invoices, and the old `cascade = ALL` would have silently deleted the sales orders). |
| PurchaseOrder | GoodsReceiptNote | `cascade = ALL` | Separate documents. Deleting is only allowed while the order is modifiable. |
| SalesOrder | PickingJob, Invoice | `cascade = ALL` | Separate documents created for the order. |
| Item | GRN / PO / SO / PickingJob / Invoice lines, Backorder | no cascade | The item is referenced, not the owner. These cascades were removed (they could delete order lines when an item was deleted). `ItemController` now refuses to delete an item still referenced by any of these. |
| ItemPrice, ItemCost | SalesOrderLine, PurchaseOrderLine | no cascade | History rows are referenced by lines, never the owner of them. |
| StockAdjustment | Item, WarehouseSection | many-to-one only | Audit record that points at what it changed. |

## Inheritance used by these classes

* `AbstractOrder<L extends OrderLine>` (`@MappedSuperclass`): shared `date`, `getTotal()` template method, abstract `getLines()`,
  `isModifiable()`, `getStatusLabel()`, `getOrderType()`, `getCounterparty()`. `PurchaseOrder` and `SalesOrder` extend it.
* `Party` (`@MappedSuperclass`): shared name and address. `Vendor` and `Customer` extend it.
* `OrderLine` interface: implemented by `PurchaseOrderLine` and `SalesOrderLine`.

`@MappedSuperclass` was chosen over `SINGLE_TABLE` / `JOINED` so the schema and the native queries stay untouched.

## Avoiding circular references

The entities no longer use Lombok `@Data`. It generated `equals`, `hashCode` and `toString` over every field, including
both sides of each bidirectional association (order <-> lines, vendor <-> orders), which recurses forever and also
forces lazy collections to load. Entities now use `@Getter` / `@Setter` only, so they use identity equality and the default
`toString`, and there are no cycles to exclude.
