# OOM concepts and where to find them

All Java paths are under `src/main/java/com/example/warehouseManagement/`.

## Classes and objects
Entities in `Domains/` model the business: `Item`, `Stock`, `Vendor`, `Customer`, `SalesOrder`, `PurchaseOrder`, `PickingJob`, `GoodsReceiptNote`, `Shipment`, `TrackingEvent`, `StockAdjustment`. Each instance is one row (an object) at runtime.

## Attributes, methods, constructors
- `Domains/Shipment.java`: static factory `Shipment.create(PickingJob, String, String, ...)` and `create(PickingJob, String, String)`; behaviour methods `assignCarrier`, `transitionTo`, `applyShippingCost`, `getTotalUnits`.
- JPA requires a no-arg constructor, so entities use Lombok `@NoArgsConstructor` plus `@SuperBuilder` (`Domains/Party.java`, `Domains/AbstractOrder.java`).
- `Services/ShipmentServiceImpl.java`: constructor injection of repositories.

## Encapsulation
- `Domains/Stock.java`: `qtyOnHand` is private; it changes only through `increase`, `decrease` and `setCountedQty`, which reject negative or excessive quantities.
- `Domains/SalesOrder.java`: lines change only through `addLine` / `removeLine`; status through `markPartiallyShipped()` / `markShipped()`.
- `Domains/Shipment.java`: status changes go through `transitionTo`, and history is read through `getTrackingEvents()`.
- Test: `src/test/java/com/example/warehouseManagement/Domains/OrderEncapsulationTest.java`.

## Abstraction
- `Domains/AbstractOrder.java`: abstract `getLines()`, `isModifiable()`, `getStatusLabel()`, `getOrderType()`, `getCounterparty()`.
- `Domains/Party.java`: abstract `getPartyType()`.
- `Domains/OrderLine.java`: interface (`getItem`, `getQty`, `getSubtotal`).
- Services are interfaces (`Services/ShipmentService.java`, `Services/GoodsReceiptNoteService.java`, ...).

## Inheritance
- `Vendor` and `Customer` extend `Party` (shared name/address, `formattedAddress()`).
- `PurchaseOrder` and `SalesOrder` extend `AbstractOrder<L extends OrderLine>`.
- `PurchaseOrderLine` and `SalesOrderLine` implement `OrderLine`.
- `@MappedSuperclass` was chosen over table inheritance so no schema change was needed.
- Exceptions: `Domains/Exceptions/ShipmentException.java` (abstract) is the parent of `DuplicateTrackingNumberException` and `InvalidShipmentStatusTransitionException`.
- Tests: `Domains/PartyInheritanceTest.java`, `Domains/AbstractOrderPolymorphismTest.java`.

## Polymorphism
Compile-time (overloading):
- `Stock.increase(int)` / `increase(int, String)`, `Stock.decrease(int)` / `decrease(int, String)`.
- `Shipment.transitionTo(ShipmentStatus)`, 3-argument and 4-argument overloads; `Party.formattedAddress()` / `formattedAddress(String)`.
- `ShipmentServiceImpl.createShipment` (two overloads).

Runtime (overriding):
- `AbstractOrder.getTotal()` calls the abstract `getLines()`, which `SalesOrder` and `PurchaseOrder` implement differently; callers can treat both as `AbstractOrder<?>` and call `getStatusLabel()`, `isModifiable()`, `getOrderType()`.
- `Vendor.getPartyType()` vs `Customer.getPartyType()`.
- `ShipmentStatus.allowedNext()` selects behaviour per enum constant.
- `ShippingStrategy.calculateCost` / `estimateDeliveryDate`: `ShipmentServiceImpl` calls the interface; the strategy picked by `ShippingStrategyFactory` decides the result at runtime (`ShippingStrategyTest`).

## Association, aggregation, composition
Full tables in [RELATIONSHIPS.md](RELATIONSHIPS.md). Examples:
- Composition: `SalesOrder` -> `SalesOrderLine` (`cascade = ALL, orphanRemoval = true`).
- Aggregation: `Vendor` -> `Item` (no remove cascade).
- Association: `Customer` -> `SalesOrder`, `Shipment` -> `PickingJob`.
- Test: `Repositories/RelationshipLifecycleTest.java`.

## SOLID
- S: controllers handle HTTP, `Services/*Impl` hold rules, `Repositories/*` do persistence; `Services/EmailServiceImpl.java` only sends mail.
- O: a new shipping method is a new `ShippingStrategy` component, and a new reaction to a status change is a new `@EventListener`, with no change to `ShipmentServiceImpl`; a new order type extends `AbstractOrder` without touching `getTotal()`; transitions are added in `ShipmentStatus.allowedNext()` only.
- L: `PurchaseOrder` / `SalesOrder` substitute for `AbstractOrder`, `Vendor` / `Customer` for `Party` (see the polymorphism tests).
- I: small service interfaces per capability (`ShipmentService`, `StockAdjustmentService`, `SuggestionService`).
- D: controllers depend on interfaces (`ShipmentService`); `ShipmentServiceImpl` depends on `ShippingStrategyFactory` and `ApplicationEventPublisher`. Spring injects the implementations. Full report: [SOLID-REPORT.md](SOLID-REPORT.md).

## Design patterns
Details and file references: [DESIGN-PATTERNS.md](DESIGN-PATTERNS.md).
- Strategy: `Services/shipping/ShippingStrategy` and three implementations (cost and delivery date per `ShippingMethod`), used by `ShipmentServiceImpl.createShipment`.
- Factory: `Services/shipping/ShippingStrategyFactory.forMethod`.
- Observer: `ShipmentStatusChangedEvent`, `ShipmentNotificationListener`, `SalesOrderShipmentSyncListener`.
- State machine: `Domains/ShipmentStatus.java` (`allowedNext`, `canTransitionTo`, `isTerminal`), used by `Shipment.transitionTo`.
- Template method: `AbstractOrder.getTotal()`.
- Static factory: `Shipment.create(...)`.
- Builder: Lombok `@SuperBuilder` on `Party` and `AbstractOrder`.
- Dependency injection and Repository: Spring beans in `Services/` and `Repositories/`.
- Dispatch on type: `StockAdjustmentServiceImpl.apply` (switch over adjustment type).
- Singleton beans: `Security/SecurityConfig.java` (`passwordEncoder`, `securityFilterChain`).
- Data structures: `DSA/Trie.java`, `DSA/BinarySearchTree.java`; `Services/SuggestionIndex.java` builds on `Trie` for type-ahead suggestions.

## Exception handling
- Domain exceptions in `Domains/Exceptions/` (for example `ShipmentNotFoundException`, `InvalidShipmentStatusTransitionException`, `GoodsReceiptAlreadyFulfilledException`, `InvalidReceiptQuantityException`).
- Controllers map them with `@ExceptionHandler` in `Controllers/GlobalExceptionHandler.java` and `Controllers/ShipmentExceptionHandler.java`.
- Services validate and throw: `ShipmentServiceImpl.createShipment` throws `ShipmentNotAllowedException`; `Stock.decrease` throws `IllegalArgumentException`.
- Tests: `Services/ShipmentServiceImplTest.java`, `Services/GoodsReceiptNoteServiceTest.java`.

## Modularity
Packages by responsibility (`Controllers`, `Domains`, `Services`, `Repositories`, `Security`, `DSA`, `Util`, `Bootstrap`), interface + Impl per service, DTOs in `Domains/DTOs`, schema versioned in `src/main/resources/db/`.
