# Architecture

## Layers
```
Browser (Thymeleaf + Bootstrap)
  -> Controllers (Spring MVC)      HTTP, input binding, model/view
  -> Services (interface + Impl)   business rules, transactions
  -> Repositories (Spring Data)    persistence and native report queries
  -> Entities (Domains) -> H2 (file) with Flyway migrations
```
- Controllers (`Controllers/`): map requests, call a service, map domain exceptions to error views (`GlobalExceptionHandler`, `ShipmentExceptionHandler`). Example: `ShipmentController` (`/shipments`, `/shipments/new`, `/shipments/track`, `/shipments/{id}/status`).
- Services (`Services/`): rules and `@Transactional` boundaries, for example `ShipmentServiceImpl`, `PickingJobServiceImpl`, `GoodsReceiptNoteImpl`, `StockAdjustmentServiceImpl`.
- Repositories (`Repositories/`): `ShipmentRepository`, `StockRepository`, and others.
- Security (`Security/SecurityConfig.java`): form login, BCrypt, 2FA success handler.

## Entity relationships
See [RELATIONSHIPS.md](RELATIONSHIPS.md). Orders own their lines (composition), vendors reference items (aggregation), documents reference each other (association): PurchaseOrder -> GoodsReceiptNote; SalesOrder -> PickingJob -> Shipment -> TrackingEvent.

## Fulfilment workflow
1. A PurchaseOrder is raised to a Vendor; a GRN is opened (`GoodsReceiptNoteService.create` / `findOrCreatePending`).
2. `GoodsReceiptNoteService.fulfill` adds stock on the floor section; put-away moves it to a real section.
3. A SalesOrder creates a PickingJob; `PickingJobService.fulfill` decrements stock, writes the invoice and backorders.
4. Stock corrections go through `StockAdjustmentService.apply` (cycle count, write-off, transfer).

## Shipment workflow
`ShipmentServiceImpl.createShipment` accepts only an existing picking job that has no shipment yet, with a unique tracking number. Status moves are defined by `ShipmentStatus.allowedNext()`:

```
CREATED -> PACKED -> DISPATCHED -> IN_TRANSIT -> OUT_FOR_DELIVERY -> DELIVERED
DISPATCHED / IN_TRANSIT / OUT_FOR_DELIVERY -> EXCEPTION -> IN_TRANSIT | OUT_FOR_DELIVERY | RETURNED
```
DELIVERED and RETURNED are terminal. Each transition adds a `TrackingEvent`. Shipments that count as shipped update the sales order through `SalesOrder.markShipped()` / `markPartiallyShipped()`. Invalid moves throw `InvalidShipmentStatusTransitionException`.

## Pattern usage
State machine (`ShipmentStatus`), template method (`AbstractOrder.getTotal`), static factory (`Shipment.create`), builder (Lombok), dependency injection and repository (Spring), Trie-based type-ahead (`SuggestionIndex`). Details in [OOM-CONCEPTS.md](OOM-CONCEPTS.md).

## Data and schema
H2 file database, Flyway `V1` to `V3` in `src/main/resources/db/`, `spring.jpa.hibernate.ddl-auto=validate`.
