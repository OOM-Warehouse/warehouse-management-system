# Diagram versus code

Names (classes, methods, fields, URLs) were taken from the source with grep. Differences and simplifications:

1. **No ShippingStrategy, factory, events or listeners.** The brief mentions them, but `main` has none (no class named
   `ShippingStrategy`, no factory class, no `ApplicationEventPublisher` or `@EventListener`). They are not drawn.
   `ShippingMethod` is a plain enum (STANDARD, EXPRESS, OVERNIGHT) and `Shipment.create(...)` is a static factory method.
   The status rules live in `ShipmentStatus.allowedNext()`.
2. **Roles.** `User.Role` has only USER and ADMIN. `SecurityConfig` restricts `/admin/**` to ADMIN and requires login for
   everything else. The Manager and Staff actors are therefore merged into "Warehouse User". The `Admin --|> Staff`
   generalisation is a modelling choice (admins can use all pages), not a class relationship.
3. **Supplier and Customer** are external actors. They have no login. In the code they are the `Vendor` and `Customer`
   entities, and staff enter their orders and receipts.
4. **Class diagram is partial.** DTOs, repositories, `User` security fields and Lombok getters/setters are left out.
   `PoStatus` and `GrnStatus` show no values. Service interfaces list only the methods used by the fulfilment flow.
5. **Packages** are shown as groups, not exact Java package paths.
6. **Sequence and activity diagrams are simplified.** The repository calls inside `SalesOrderServiceImpl.save` are
   summarised. PACKED and later steps are driven by `POST /shipments/{id}/status`. The picking of the backorder job
   (which repeats the same flow) is not drawn again.
7. **Return handling.** After RETURNED the shipment no longer counts as shipped (`countsAsShipped()` is false), but
   `ShipmentServiceImpl.syncSalesOrderStatus` only updates the order when some shipped quantity remains. The activity
   diagram stops at RETURNED and does not model this.
