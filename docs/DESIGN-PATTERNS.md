# Design patterns

Paths are under `src/main/java/com/example/warehouseManagement/`.

## Implemented and used in the shipment workflow
| Pattern | Classes | Use |
|---|---|---|
| Strategy | `Services/shipping/ShippingStrategy` with `StandardShippingStrategy`, `ExpressShippingStrategy`, `OvernightShippingStrategy` | cost (`calculateCost`) and expected delivery date (`estimateDeliveryDate`) per `ShippingMethod`; called from `ShipmentServiceImpl.createShipment`, which stores them via `Shipment.applyShippingCost` and the expected date |
| Factory | `Services/shipping/ShippingStrategyFactory.forMethod` | receives every `ShippingStrategy` bean and returns the one that `supports` the method; `null` or an unregistered method throws `IllegalArgumentException` |
| Observer | `ShipmentStatusChangedEvent` (published by `ShipmentServiceImpl.updateStatus`), `ShipmentNotificationListener`, `SalesOrderShipmentSyncListener` | listeners react to every status change (logging, sales order status sync) without the service knowing them |

Tests: `Services/shipping/ShippingStrategyTest`, `Services/ShipmentServiceImplTest` (strategy cost and date, `@RecordApplicationEvents`, order sync).

## Other patterns
- MVC: `Controllers/`, Thymeleaf views in `resources/templates`, entities in `Domains/`.
- Repository / DAO: Spring Data interfaces in `Repositories/`.
- Dependency injection: constructor injection in all `Services/*Impl` and controllers.
- State: `Domains/ShipmentStatus` (`allowedNext`, `canTransitionTo`) used by `Shipment.transitionTo`.
- Template method: `AbstractOrder.getTotal()`.
- Static factory and Builder: `Shipment.create(...)`, Lombok `@SuperBuilder`.
