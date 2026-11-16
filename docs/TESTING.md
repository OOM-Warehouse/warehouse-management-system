# Testing

Framework: JUnit 5, Mockito, Spring Boot Test (`@DataJpaTest`, `@SpringBootTest`, MockMvc), Spring Security Test. Tests live in `src/test/java/com/example/warehouseManagement/`.

## Unit tests (pure objects)
- `Domains/ShipmentTest.java`: lifecycle, transitions, event history.
- `Domains/OrderEncapsulationTest.java`: guarded state, add/remove lines.
- `Domains/AbstractOrderPolymorphismTest.java`: runtime polymorphism of orders.
- `Domains/PartyInheritanceTest.java`: Vendor and Customer through Party.
- `DSA/TrieTest.java`, `DSA/BinarySearchTreeTest.java`.

## Integration tests (Spring context, H2)
- Services: `Services/ShipmentServiceImplTest.java` (duplicate tracking number, invalid transitions, delivery before dispatch rejected, partial vs full order shipped), `Services/GoodsReceiptNoteServiceTest.java`, `Services/PickingJobServiceTest.java`.
- Repositories, under `Repositories/`: `StockRepositoryTest`, `SalesOrderRepositoryTest`, `PurchaseOrderRepositoryTest`, `BackorderRepositoryTest`, `RelationshipLifecycleTest` (cascade and orphan-removal rules).
- Controllers (MockMvc), under `Controllers/`: `ShipmentControllerTest`, `AuthControllerTest`, `AdminUserControllerTest`, `DashBoardControllerTest`.
- `WarehouseManagementApplicationTests.java`: the application context starts.

## How to run
```bash
./mvnw clean test
mvn -o test                                # offline, local Maven cache
mvn -o test -Dtest=ShipmentServiceImplTest
```
If the clean plugin is not cached offline, skip `clean` and delete `target/` by hand.
