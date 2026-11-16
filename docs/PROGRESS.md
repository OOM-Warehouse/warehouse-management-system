# Progress

Agreed plan: [`docs/audit/IMPLEMENTATION-PLAN.md`](audit/IMPLEMENTATION-PLAN.md). Tick an item only when the implementation **and** its tests support it.

## Assignment checklist

- [x] Inventory operations work correctly and prevent invalid stock changes
- [x] Supplier management works
- [x] Purchase orders support complete and partial receiving
- [x] Picking supports complete and partial fulfilment
- [x] Backorders are generated correctly
- [x] Shipment creation and tracking work
- [x] Shipment status transitions are validated
- [x] Encapsulation is demonstrated through controlled domain methods
- [x] Inheritance and runtime polymorphism are demonstrated in actual workflows
- [x] Association, aggregation and composition are justified and documented
- [x] SOLID principles have concrete code examples
- [x] At least two suitable design patterns are implemented and used
- [x] Exception handling works for invalid business operations
- [x] All four UML diagrams are present and match the code
- [x] Important unit and integration tests pass
- [x] README and OOM documentation are complete
- [x] Final Maven build and application startup succeed (113 tests green; app started on port 8099, /login 200, /shipments 302 to login)
