# Progress

Agreed plan: [`docs/audit/IMPLEMENTATION-PLAN.md`](audit/IMPLEMENTATION-PLAN.md). Tick an item only when the implementation **and** its tests support it.

## Assignment checklist

- [x] Inventory operations work correctly and prevent invalid stock changes
- [ ] Supplier management works (partial: no vendor service test)
- [x] Purchase orders support complete and partial receiving
- [x] Picking supports complete and partial fulfilment
- [x] Backorders are generated correctly
- [x] Shipment creation and tracking work
- [x] Shipment status transitions are validated
- [x] Encapsulation is demonstrated through controlled domain methods
- [x] Inheritance and runtime polymorphism are demonstrated in actual workflows
- [x] Association, aggregation and composition are justified and documented
- [ ] SOLID principles have concrete code examples
- [ ] At least two suitable design patterns are implemented and used
- [x] Exception handling works for invalid business operations
- [x] All four UML diagrams are present and match the code
- [x] Important unit and integration tests pass
- [ ] README and OOM documentation are complete
- [ ] Final Maven build and application startup succeed

## Stage log

| Stage | Scope | Prompt(s) | Status |
|---|---|---|---|
| 0 | Repository, functional, OOM and UML audits (`docs/audit/01`, `02a`, `02b`, `02c`) | 1–2 | Done (read-only; baseline 32 tests passing) |
| 1 | Implementation plan and conflict resolution (`docs/audit/IMPLEMENTATION-PLAN.md`) | 2 | Done |
| 2 | Bug fixes: inventory/supplier/PO/GRN (Prompt 3), then sales order/picking/backorders (Prompt 4). No migration | 3, 4 | Not started |
| 3 | Shipment module (Flyway `V3`), in parallel with Stage 4 | 5 | Not started |
| 4 | OOM refactor: encapsulation, `AbstractOrder`/`Party`, exception hierarchy, overloads, cascades (Flyway `V4` only if needed) | 6–8 | Not started |
| 5 | Design patterns: Strategy ×2, Observer, Facade/State documentation; integration of the Stage 3 and 4 work | 9 | Not started |
| 6 | SOLID targets (SRP, OCP, LSP, ISP, DIP) | 10 | Not started |
| 7 | Exception handling and validation hardening, test gap closure | later | Not started |
| 8 | UML (use-case, class, sequence, activity), README and OOM documentation | later | Not started |
| 9 | Final verification: full `mvn -o test`, application startup, end-to-end smoke test | later | Not started |
