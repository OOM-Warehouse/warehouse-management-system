# SOLID report

One concrete example per principle. Paths are under `src/main/java/com/example/warehouseManagement/`.

| Principle | Example | Where |
|---|---|---|
| SRP | Each class has one reason to change: `ShipmentController` handles HTTP, `ShipmentServiceImpl` orchestrates use cases, `Shipment` guards its own state, `ShipmentRepository` persists, `SalesOrderShipmentSyncListener` only syncs the order status, `ShipmentNotificationListener` only logs. | `Controllers/ShipmentController`, `Services/ShipmentServiceImpl`, `Services/shipping/*Listener` |
| OCP | A new shipping method needs a new `ShippingStrategy` `@Component` and an enum constant; `ShipmentServiceImpl` and `ShippingStrategyFactory` are not edited. A new reaction to a status change is a new `@EventListener`, with no change to `updateStatus`. | `Services/shipping/ShippingStrategy`, `StandardShippingStrategy`, `ExpressShippingStrategy`, `OvernightShippingStrategy` |
| LSP | `SalesOrder` and `PurchaseOrder` substitute for `AbstractOrder` (`getTotal()`, `getStatusLabel()`); `Vendor` and `Customer` for `Party`; every `ShippingStrategy` honours the same contract (non-negative cost, date after dispatch), so callers never check the concrete type. | `Domains/AbstractOrder`, `Domains/Party`; tests `AbstractOrderPolymorphismTest`, `PartyInheritanceTest`, `ShippingStrategyTest` |
| ISP | Small, role-focused interfaces rather than one large service: `ShipmentService`, `StockService`, `BackorderService`, `InvoiceService`; `ShippingStrategy` has only three methods. | `Services/*Service.java` |
| DIP | `ShipmentServiceImpl` depends on the abstractions `ShippingStrategyFactory` / `ShippingStrategy` and `ApplicationEventPublisher`, not on concrete strategies or on `SalesOrderRepository`; the controller depends on the `ShipmentService` interface. Spring injects the implementations. | `Services/ShipmentServiceImpl` constructor |

## The refactor
Before, `ShipmentServiceImpl.updateStatus` called a private `syncSalesOrderStatus` and had no pricing. Now it publishes a `ShipmentStatusChangedEvent`; `SalesOrderShipmentSyncListener` holds the sync rule (moved unchanged), so the service no longer knows about `SalesOrderRepository` (SRP, OCP, DIP). Cost and delivery estimates come from the strategy chosen by the factory.
