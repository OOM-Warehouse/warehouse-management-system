# 02b - OOM Audit (Object-Oriented Methodology)

Scope: read-only audit of `src/main/java/com/example/warehouseManagement/**` at commit `3143c03`
(branch `claude/spring-warehouse-refactor-b6ffc5`). No source, test or config file was modified.
All file paths below are relative to `src/main/java/com/example/warehouseManagement/` unless stated.

Constraints considered throughout:

- Flyway owns the schema (`src/main/resources/db/migration/V1__baseline.sql`, `V2__stock_adjustments.sql`)
  and `spring.jpa.hibernate.ddl-auto=validate`. Any persistent-field change needs a `V3__*.sql`.
- Naming: `ImplicitNamingStrategyLegacyJpaImpl` + `PhysicalNamingStrategyStandardImpl`; ids use
  `@GeneratedValue(AUTO)` backed by per-table sequences `<entity>_SEQ` (increment 50).
- All four status enums are persisted **ORDINAL** (`tinyint` with `check` constraints) and 30+ native
  queries in `Repositories/*.java` hard-code ordinals (`status = 0`, `status IN (0, 2)`, `status = 1`).
  **Enum constant order is therefore part of the schema contract** and must not change.
- Lombok 1.18.30 (supports `@SuperBuilder`). No new dependencies.

---

## 1. Executive scorecard

| Concept | Verdict | Where it is (best evidence) | Main gap |
|---|---|---|---|
| Classes / objects | Genuine | 21 `@Entity` classes in `Domains/`, services, DSA | Entities are mostly data holders |
| Attributes / methods | Partly genuine | `PurchaseOrder.getTotal()`, `SalesOrderLine.getSubtotal()`, `GoodsReceiptNote.getTotalQty()`, `LastThreeMonthsSales` | Business rules live in services, not domain |
| Constructors | Genuine but mostly generated | Hand-written DI constructors in every `*ServiceImpl`; overloaded ctors in `Util/Counter`, `Domains/Exceptions/*` | Entity ctors are all Lombok-generated; no invariant-enforcing ctor |
| Encapsulation | **Weak** | `DSA/Trie`, `DSA/BinarySearchTree` (private inner nodes), `SuggestionIndex`, `StockAdjustmentServiceImpl` private helpers | `@Data` on every entity exposes `setId`, `setStatus`, `setXxxLines`; status changed ad hoc from services |
| Abstraction | Genuine (layer level) | 21 service interfaces (`Services/*Service.java`), Spring Data repositories, `EmailService` | No domain-level abstraction (no abstract class / domain interface) |
| Inheritance | **Superficial** | Exceptions extend `Exception`/`RuntimeException`; `TwoFactorAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler`; `UserService extends UserDetailsService` | No inheritance anywhere in the domain model; no exception hierarchy |
| Compile-time polymorphism | Genuine but small | Overloads: `findAll()` / `findAll(Pageable)` (PO, SO services), `Counter()` / `Counter(int)`, exception ctors, `BinarySearchTree.inOrder()` / `inOrder(TreeNode)`; generics `BinarySearchTree<T extends Comparable<T>>` | Not in domain code |
| Runtime polymorphism | **Superficial** | Interface -> single impl via DI; `onAuthenticationSuccess` override; `Counter.toString()`; `Specification` lambdas | Every service interface has exactly one impl; `StockAdjustmentServiceImpl.apply` uses a `switch` where polymorphism belongs |
| Association | Genuine | `@ManyToOne` `PurchaseOrder -> Vendor`, `SalesOrder -> Customer`, `Item -> Vendor`, `Stock -> Item/WarehouseSection` | Bidirectional lists everywhere; some never used |
| Aggregation | Genuine (implicit) | `Vendor` 1..* `Item`, `Warehouse` 1..* `WarehouseSection`, `WarehouseSection` 1..* `Stock` | Not distinguished from composition in mappings (`Vendor.items` uses `CascadeType.ALL`) |
| Composition | Genuine (implicit) | `PurchaseOrder` *-- `PurchaseOrderLine`, `SalesOrder` *-- `SalesOrderLine`, `GoodsReceiptNote` *-- `GoodsReceiptNoteLine`, `PickingJob` *-- `PickingJobLine`, `Invoice` *-- `InvoiceLine` | No `orphanRemoval`; lines are created/saved by services via their own repositories rather than through the aggregate root; no value object (`Address`) |
| SRP | Mixed | Small focused services (`EmailServiceImpl`, `StockAdjustmentServiceImpl`) | `PickingJobController.fulfillPickingJob` orchestrates 3 services; `Bootstrap` 400+ lines; repositories mix CRUD + reporting |
| OCP | Weak | `SuggestionServiceImpl` uses `EnumMap<Type, SuggestionIndex>` | `StockAdjustmentServiceImpl.apply` switch; adding an adjustment type edits the service |
| LSP | N/A / trivially satisfied | Only framework subclasses | Nothing to substitute in domain |
| ISP | Mixed | `EmailService` (2 methods), `SuggestionService` | `PurchaseOrderService`/`SalesOrderService` mix CRUD, search and dashboard KPI methods (15-18 methods) |
| DIP | Genuine | Constructor injection of interfaces everywhere; controllers depend on `*Service` interfaces | `SearchController` depends directly on 3 repositories; `PickingJobServiceImpl` depends on 6 repositories |
| Design patterns | Several genuine, under-documented | Repository, Service Layer, DTO, Builder (Lombok), Specification, Front Controller/MVC, Template Method (Spring handler), Facade (`SuggestionIndex` over `Trie`), Registry (`SuggestionServiceImpl`), Static Factory/Object Pool-like seed data (`*Pool`) | No Strategy / State in domain logic, which is where they would be most convincing |

Overall: the project is a solid **layered Spring MVC application**, and layer-level abstraction + DIP are
real. The **domain model is anaemic** (Lombok `@Data` bags + services that mutate them), which is exactly
where an OOM marker will look for encapsulation, inheritance and runtime polymorphism. A small, targeted
change set (Section 10) fixes that without touching the schema (no Flyway migration needed).

---

## 2. Classes, objects, attributes, methods, constructors

### What is genuine

- **Entities with behaviour (small but real):**
  - `Domains/PurchaseOrder.getTotal()` and `Domains/SalesOrder.getTotal()` - derived attribute computed
    from composed lines.
  - `Domains/PurchaseOrderLine.getSubtotal()`, `Domains/SalesOrderLine.getSubtotal()`.
  - `Domains/GoodsReceiptNote.getTotalQty()`.
  - `Domains/Vendor.formattedAddress()`, `Domains/Customer.formattedAddress()` (duplicated - see 6.3).
  - `Domains/ItemPrice.formattedEndDate()`.
  - `Domains/LastThreeMonthsSales.getThreeMonthsAverageSales()`, `getMaxValue()` - a non-entity domain
    object with real computation.
- **Nested enums as part of the class's type:** `PurchaseOrder.PoStatus` (with a field + constructor +
  accessor - a mini class in itself), `SalesOrder.SoStatus`, `GoodsReceiptNote.GrnStatus`,
  `PickingJob.PjStatus`, `StockAdjustment.AdjustmentType`, `User.Role`.
- **Hand-written classes with full OOP shape:** `DSA/BinarySearchTree<T>` (private inner `TreeNode`,
  private recursive helpers, public API), `DSA/Trie` (private inner `TrieNode` with accessors),
  `Services/SuggestionIndex` (private state, public `add/findByPrefix/findContaining`, nested `record Match`),
  `Util/Counter`.
- **Constructors:**
  - Every `*ServiceImpl`, `TwoFactorAuthenticationSuccessHandler`, controllers: explicit constructor
    injection with `final` fields - a clean demonstration of "constructor establishes a valid object".
  - Overloaded constructors: `Util/Counter()` / `Counter(int init)`; every checked exception in
    `Domains/Exceptions/*` has `()` and `(String message)` with constructor chaining via `super(...)`.
  - `PurchaseOrder.PoStatus(int pk)` enum constructor.

### What is superficial / weak

- All 21 entity constructors are Lombok-generated (`@NoArgsConstructor @AllArgsConstructor @Builder`).
  The all-args constructor and builder allow constructing an order with `status = RECEIVED` and no lines;
  no constructor or factory enforces invariants.
- `PurchaseOrder.PoStatus.pk` (0,1,2) duplicates `ordinal()` and is not used for persistence; it is
  misleading (persistence is ordinal, not `pk`). `SalesOrder.SoStatus` comments say `//2`, `//3` for
  `PARTIALLY_SHIPPED`, `SHIPPED`, but the stored ordinals are 1 and 2 (native queries use `IN (0, 1)`).
- Many `@Column(name = "...")` annotations sit on `@OneToMany` collections (e.g. `Item.itemPrices`,
  `SalesOrder.invoices`) and one on a method (`Vendor.formattedAddress()` / `Customer.formattedAddress()`).
  They are ignored by Hibernate - harmless, but a reviewer may read them as a misunderstanding.

### Smallest improvement

- Add intent-revealing domain methods (Section 10, item 2) instead of new constructors. JPA requires the
  no-arg constructor, so keep `@NoArgsConstructor`; Lombok builders can stay for Bootstrap/tests.

---

## 3. Encapsulation

### Genuine examples

- `DSA/Trie`, `DSA/BinarySearchTree`: node classes are `private` inner classes; internal structure is
  never exposed.
- `Services/SuggestionIndex`: three private maps kept consistent only through `add(...)`; returns new
  lists from `materialize`.
- `Services/SuggestionServiceImpl.refresh()`: builds a new `EnumMap` then swaps under `synchronized`.
- `Services/StockAdjustmentServiceImpl`: `setQtyAt / decrementAt / incrementAt` are private and guard
  invariants ("cannot adjust below zero").
- `Services/EmailServiceImpl.send(...)`: private helper hidden behind the `EmailService` interface.

### Weaknesses (the most important finding of this audit)

1. **Lombok `@Data` on every entity** (`Domains/*.java`, 23 occurrences) generates public setters for:
   - `id` (identity should never be reassigned),
   - `status` on `PurchaseOrder`, `SalesOrder`, `GoodsReceiptNote`, `PickingJob`,
   - the composed collections (`setPurchaseOrderLines`, `setSaleOrderLines`, `setPickingJobLines`, ...).
   Any service or controller can therefore put an aggregate into an illegal state.
2. **State transitions are scattered and unguarded** - status is written with raw setters from services:
   - `Services/GoodsReceiptNoteImpl.fulfill` lines 133-136: sets `PARTIALLY_RECEIVED` and then
     **unconditionally overwrites it with `RECEIVED`**. This is a real defect caused by missing
     encapsulation: partial receipts are reported as fully received, and `PurchaseOrderServiceImpl.updateById`
     will then refuse further edits.
   - `Services/PickingJobServiceImpl.fulfill` line 210 sets `PjStatus.FULFILLED`.
   - `Services/SalesOrderServiceImpl.updateById` line 154 sets `SHIPPED`.
   - `Bootstrap/Bootstrap.java` line 404 sets `PjStatus.FULFILLED`.
   - **`SalesOrder` is never moved to `PARTIALLY_SHIPPED` or `SHIPPED` by the fulfilment flow**
     (`PickingJobController.fulfillPickingJob` -> `PickingJobServiceImpl.fulfill` ->
     `StockServiceImpl.pickStock` -> `InvoiceServiceImpl.createByPickingJob`). Only seed data has shipped
     orders. Order-fulfilment/shipment tracking is therefore incomplete at the domain level.
   - The "can this order still be modified?" rule is duplicated in
     `PurchaseOrderServiceImpl.updateById`, `SalesOrderServiceImpl.updateById`,
     `SalesOrderController.removeSaleOrderLineFromUpdateOrderForm` (line 272 - and it checks the
     *form-bound* object, whose status is always the default `PENDING`, so the guard never fires) and
     `SalesOrderController.deleteSalesOrder` (line 323).
3. **`@Data` `equals/hashCode/toString` over bidirectional associations** (e.g. `SalesOrder.saleOrderLines`
   <-> `SalesOrderLine.salesOrder`, `Vendor.items` <-> `Item.vendor`). Calling `toString()`/`hashCode()`
   recurses until `StackOverflowError` and also triggers lazy loading. It does not fail today only because
   nothing puts entities in a `HashSet` or logs them. This is a known JPA + Lombok anti-pattern and an easy
   point for an examiner.
4. Collections are exposed mutably (`getSaleOrderLines().add(...)`, `.remove(i)`), so the aggregate root
   cannot maintain the back-reference (`line.setSalesOrder(this)`) - services do it manually
   (`SalesOrderServiceImpl.save`, `PurchaseOrderServiceImpl.save`).

### Smallest reasonable change (no schema change)

- On the four stateful entities, replace class-level `@Data` with `@Getter @Setter` and add
  `@Setter(AccessLevel.NONE)` on `id` and `status`. Hibernate uses **field access** here (the `@Id` is on
  a field), so it needs no setter. Spring MVC form binding does not bind `status` on entity forms (the
  `*{status}` fields in list templates bind the `Advanced*SearchCriteria` DTOs, verified in
  `templates/salesOrders/salesOrders.html` line 81 etc.), and `DataBinder` ignores non-writable properties.
- Add `@ToString.Exclude` / `@EqualsAndHashCode.Exclude` (or drop `@EqualsAndHashCode` in favour of an
  id-based implementation) on the bidirectional collections and back-references. Even the minimal step -
  `@ToString(exclude=...)` + `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` with `@EqualsAndHashCode.Include`
  on `id` - removes the recursion.
- Introduce state-transition methods (Section 10, item 2) and make services call them.

---

## 4. Abstraction

### Genuine

- Every business area has an interface + implementation: `PurchaseOrderService`/`PurchaseOrderServiceImpl`,
  `SalesOrderService`/`SalesOrderServiceImpl`, `GoodsReceiptNoteService`/`GoodsReceiptNoteImpl`,
  `PickingJobService`, `StockService`, `StockAdjustmentService`, `InvoiceService`, `VendorService`,
  `CustomerService`, `ItemService`, `ItemPriceService`, `ItemCostService`, `UserService`
  (`extends UserDetailsService`), `EmailService`, `SuggestionService`, `BackorderService`,
  `WarehouseSectionService`, line services.
- Spring Data repository interfaces (`Repositories/*Repository.java`) abstract persistence entirely.
- `SuggestionIndex` abstracts the raw `Trie` behind a case-insensitive, id-carrying API.

### Superficial

- Every interface has **exactly one** implementation. `Services/GoodsReceiptNoteImplBack.java` is a 153-line
  file that is **entirely commented out** - it looks like a second implementation but is not compiled.
  It should be deleted (or moved to docs) because an examiner may cite it as an example of polymorphism
  that does not exist.
- There is no abstract class anywhere in `src/main/java`, and no domain-level interface (e.g. `OrderLine`).

### Smallest improvement

- `OrderLine` interface + `AbstractOrder` `@MappedSuperclass` (Section 7) gives domain abstraction.
- A Strategy interface for stock adjustments (Section 10, item 3) gives an interface with several genuine
  implementations.

---

## 5. Inheritance

### Current state

| Subclass | Superclass / super-interface | Kind |
|---|---|---|
| `Domains/Exceptions/*NotFoundException`, `Received/ShippedOrderModificationException` | `java.lang.Exception` | framework |
| `DuplicateUserException`, `InvalidTokenException`, `UserNotFoundException` | `RuntimeException` | framework |
| `Security/TwoFactorAuthenticationSuccessHandler` | `SimpleUrlAuthenticationSuccessHandler` (overrides `onAuthenticationSuccess`) | framework, genuine override |
| `Services/UserService` | `UserDetailsService` | interface inheritance |
| `Repositories/*` | `JpaRepository` / `CrudRepository` / `JpaSpecificationExecutor` | framework |
| `Bootstrap/Bootstrap` | `implements CommandLineRunner` | framework |

There is **no inheritance in the domain model**, and the 11 exceptions are flat siblings - there is no
common `WarehouseException`, no `EntityNotFoundException` family, no `OrderModificationException` family,
even though they are handled identically in `Controllers/GlobalExceptionHandler`.

### Where inheritance is justified (and where it is not)

- **Justified:** `PurchaseOrder` / `SalesOrder` share `date`, a status-based "modifiable" rule, lines with
  `getSubtotal()`, and an identical `getTotal()` loop -> `@MappedSuperclass AbstractOrder` (Section 7).
- **Justified:** exception hierarchy - zero risk, keeps every existing `@ExceptionHandler` working (Spring
  picks the most specific handler), and enables a single fallback handler for the base type.
- **Not justified:** a global `BaseEntity` for all 21 entities. It would only hoist `id`, add Lombok
  `@SuperBuilder` churn to every builder call in `Bootstrap` and tests, and demonstrate nothing new.
- **Not justified:** JPA `@Inheritance` (`SINGLE_TABLE`/`JOINED`) for orders or for `Customer`/`Vendor`.
  It requires schema changes (discriminator or join tables), data migration and rewriting the native
  queries, for no behavioural benefit.
- **Customer / Vendor:** they share six columns and `formattedAddress()`. Model this with **composition**
  (`@Embeddable Address`), not inheritance - "a vendor *has* an address" is the correct relationship and
  it avoids a `Party` superclass that has no shared behaviour.

---

## 6. Polymorphism, association, aggregation, composition

### 6.1 Compile-time (static) polymorphism

Genuine instances:

- Method overloading: `PurchaseOrderService.findAll()` vs `findAll(Pageable)`, same in `SalesOrderService`;
  `BinarySearchTree.inOrder()` vs `inOrder(TreeNode)`; `isBalanced()` vs `isBalanced(TreeNode)`.
- Constructor overloading: `Util/Counter()` / `Counter(int)`; all checked exceptions `()` / `(String)`.
- Parametric polymorphism: `DSA/BinarySearchTree<T extends Comparable<T>>` (bounded generic), Spring Data
  generic repositories, `Specification<PurchaseOrder>`.

Gap: none of this is in warehouse domain code. The `AbstractOrder<L extends OrderLine>` generic in Section 7
adds a domain-level bounded generic for free.

### 6.2 Runtime (dynamic) polymorphism

Genuine instances:

- `TwoFactorAuthenticationSuccessHandler.onAuthenticationSuccess` overrides the Spring superclass method
  and is invoked via the `AuthenticationSuccessHandler` reference (true dynamic dispatch).
- `Counter.toString()` overrides `Object.toString()` (missing `@Override`).
- `Specification<T>` lambdas in `PurchaseOrderServiceImpl.buildSpec`, `SalesOrderServiceImpl.buildSpec`,
  `PickingJobServiceImpl.buildSpec`, `GoodsReceiptNoteImpl.findAdvanced` - dispatched through the
  `toPredicate` interface method.
- Controllers call `*Service` interfaces; dispatch resolves to the impl (and to a Spring proxy for
  `@Transactional`). This is real, but with one impl per interface it is weak evidence.

Superficial / missing:

- `Services/StockAdjustmentServiceImpl.apply` (lines 21-31) is a `switch` over `AdjustmentType` - the
  textbook "replace conditional with polymorphism" case.
- `PurchaseOrder.getTotal()` vs `SalesOrder.getTotal()` are unrelated methods; no caller treats them
  uniformly.

Smallest change: Strategy for stock adjustments (Section 10, item 3) + `AbstractOrder.getTotal()` /
`isModifiable()` overridden per subtype (Section 7).

### 6.3 Association, aggregation, composition

| Relationship | Classes | UML kind | Mapping evidence | Notes |
|---|---|---|---|---|
| Order -> party | `PurchaseOrder.vendor`, `SalesOrder.customer`, `Invoice.customer` | association (many-to-one) | `@ManyToOne @JoinColumn` | genuine |
| Line -> catalogue | `*Line.item`, `PurchaseOrderLine.itemCost`, `SalesOrderLine.itemPrice` | association | `@ManyToOne` | genuine; price/cost snapshot is good modelling |
| Vendor supplies Items | `Vendor.items` / `Item.vendor` | aggregation (items can outlive vendor conceptually) | `@OneToMany(cascade = ALL)` | cascade ALL contradicts aggregation: deleting a vendor deletes its items and (transitively via `Item`'s cascades) all their order lines |
| Warehouse has Sections | `Warehouse.sections` | aggregation/composition | `@OneToMany(cascade = ALL)` | acceptable as composition |
| Section holds Stock | `WarehouseSection.stocks` | aggregation | `@OneToMany` (no cascade) | genuine aggregation |
| Order *-- Lines | `PurchaseOrder.purchaseOrderLines`, `SalesOrder.saleOrderLines`, `GoodsReceiptNote.goodsReceiptNoteLines`, `PickingJob.pickingJobLines`, `Invoice.invoiceLines` | composition | `@OneToMany(mappedBy, cascade = ALL)` | genuine lifecycle dependency; but no `orphanRemoval = true`, and services persist lines through separate repositories instead of through the root |
| SalesOrder -> PickingJob / Invoice / Backorder | `SalesOrder.pickingJobs`, `invoices`, `backorders` | composition-ish | `cascade = ALL` | fine |
| Customer/Vendor address | six flat fields duplicated | **missing value object** | - | best place to show composition with `@Embeddable` |

Smallest change for composition: `@Embeddable Address { street, city, state, zipcode; format() }` used as
`@Embedded private Address address;` in `Customer` and `Vendor`. Column names (`street`, `city`, `state`,
`zipcode`) are identical in both tables, so **no Flyway migration is required** and `validate` passes.
Cost: templates and `Bootstrap`/`*Pool`/services that use `getStreet()` etc. must switch to
`getAddress().getStreet()` (or keep delegating getters on `Customer`/`Vendor` to avoid template churn -
recommended, so templates are untouched). `CustomerServiceImpl`/`VendorServiceImpl` `Specification`s that
call `root.get("city")` must become `root.get("address").get("city")` - check before implementing.

Also consider:

- Add domain methods on aggregate roots that keep both sides of the relation consistent, e.g.
  `SalesOrder.addLine(SalesOrderLine l) { l.setSalesOrder(this); saleOrderLines.add(l); }`. This is the
  observable proof of composition (the root controls part lifecycle).
- Reduce `Vendor.items` cascade from `ALL` to `{PERSIST, MERGE}` to make it a true aggregation. Behaviour
  change on vendor delete - only do this with a test and after checking `VendorController` delete flow.

---

## 7. Do `PurchaseOrder` and `SalesOrder` justify a shared `@MappedSuperclass`?

### Side-by-side

| Aspect | `PurchaseOrder` | `SalesOrder` | Shareable? |
|---|---|---|---|
| `id` `@GeneratedValue(AUTO)` | yes (`purchase_order_SEQ`) | yes (`sales_order_SEQ`) | yes, but see risk below |
| `date` `LocalDate`, default `now()`, column `date` | yes | yes | **yes** |
| counter-party | `Vendor vendor` (`vendor_id`) | `Customer customer` (`customer_id`) | no (different types/columns) |
| lines | `List<PurchaseOrderLine> purchaseOrderLines` | `List<SalesOrderLine> saleOrderLines` | via abstract accessor only; field names are referenced by JPQL, `Specification`s and 31 template expressions - do not rename |
| line subtotal | `qty * itemCost.cost` | `qty * itemPrice.price` | yes, both already have `getSubtotal()` |
| `getTotal()` | identical loop over lines | identical loop | **yes** (Template Method) |
| status enum | `PoStatus {IN_TRANSIT, RECEIVED, PARTIALLY_RECEIVED}` ordinal | `SoStatus {PENDING, PARTIALLY_SHIPPED, SHIPPED}` ordinal | no shared field (different enum types; generic enum attribute in a mapped superclass is fragile with ordinal mapping) |
| "is closed/modifiable" rule | `status == RECEIVED` | `status == SHIPPED` | **yes, as an abstract method** |
| downstream documents | `goodsReceiptNotes` | `pickingJobs`, `invoices`, `backorders` | no |

### Verdict

**Yes, but a thin one.** The shared state is small (`date`, optionally `id`), but the shared *behaviour* is
real and currently duplicated: `getTotal()`, the closed/modifiable rule (copied into two services and two
controller methods), and the line contract. That makes it a legitimate, non-cosmetic demonstration of
inheritance + abstraction + runtime polymorphism + Template Method. A heavier hierarchy (shared status,
shared counter-party, JPA table inheritance) is **not** justified.

### Recommended shape

```java
// Domains/OrderLine.java  (plain interface, no JPA impact)
public interface OrderLine {
    Item getItem();
    int getQty();
    double getSubtotal();   // already implemented by both line classes
}

// Domains/AbstractOrder.java
@MappedSuperclass
@Getter @Setter @NoArgsConstructor @SuperBuilder
public abstract class AbstractOrder<L extends OrderLine> {
    @Column(name = "date")
    @Builder.Default
    private LocalDate date = LocalDate.now();

    /** Subclass exposes its own mapped collection (field names stay unchanged). */
    public abstract List<L> getLines();

    /** Template method: algorithm fixed here, data supplied by subclass. */
    public double getTotal() {
        return getLines().stream().mapToDouble(OrderLine::getSubtotal).sum();
    }

    /** Hook: each order type defines when it is closed for edits. */
    public abstract boolean isModifiable();
}
```

- `PurchaseOrder extends AbstractOrder<PurchaseOrderLine>`: `getLines()` returns `purchaseOrderLines`;
  `isModifiable()` returns `status != PoStatus.RECEIVED`.
- `SalesOrder extends AbstractOrder<SalesOrderLine>`: `getLines()` returns `saleOrderLines`;
  `isModifiable()` returns `status != SoStatus.SHIPPED`.
- `PurchaseOrderLine implements OrderLine`, `SalesOrderLine implements OrderLine` (their existing getters
  already satisfy the interface).
- Remove the duplicated `getTotal()` bodies from both subclasses (templates calling `order.total` /
  `getTotal()` keep working).
- Replace the four inline status checks with `isModifiable()`.

JPA / Flyway / Lombok notes:

- **No migration needed**: the `date` column already exists with the same name on both tables. `validate`
  passes as long as the column name/type are unchanged.
- **Keep `@Id` in the subclasses** for the minimal change. Hoisting `@Id @GeneratedValue(AUTO)` into the
  mapped superclass should still resolve to `purchase_order_SEQ` / `sales_order_SEQ` in Hibernate 6.4
  (implicit sequence naming uses the entity name), but it is the one part with schema risk; if attempted,
  it must be verified by `WarehouseManagementApplicationTests` (context + `validate`) and the repository
  tests, not assumed.
- Lombok: once a persistent field lives in the superclass, `PurchaseOrder.builder().date(...)` (used in
  `Bootstrap` lines 219/374 and `PurchaseOrderRepositoryTest`, `SalesOrderRepositoryTest`,
  `BackorderRepositoryTest`) only works with **`@SuperBuilder` on both the superclass and the subclasses**
  (replacing `@Builder`). `@Builder.Default` is supported by `@SuperBuilder`. Keep `@NoArgsConstructor`
  (JPA) and keep `@AllArgsConstructor` only if something calls it (nothing in `src/` does - verify).
- Use `@Getter/@Setter` on the superclass, not `@Data` (avoid generated `equals/hashCode` chains with
  `callSuper` warnings).
- Optional second step: also make `GoodsReceiptNote` / `PickingJob` share a `fulfill`-style abstraction.
  **Not recommended** - their fulfilment logic depends on repositories and DTOs; forcing a hierarchy there
  is the "unnecessary inheritance" the brief warns against.

---

## 8. SOLID

### SRP

- Good: `EmailServiceImpl` (only mail), `StockAdjustmentServiceImpl` (only adjustments),
  `SuggestionIndex` (only indexing), `GlobalExceptionHandler` (only error mapping), each `Advanced*SearchCriteria`.
- Violations:
  - `Controllers/PickingJobController.fulfillPickingJob` (lines 148-166) orchestrates three services
    (`pickingJobService.fulfill`, `stockService.pickStock`, `invoiceService.createByPickingJob`) - the
    controller holds a business workflow and **the three steps run in separate transactions**, so a failure
    in invoicing leaves stock decremented and the job fulfilled. Move the orchestration into a single
    `@Transactional` service method (e.g. `OrderFulfilmentService.fulfil(pickingJobId, dto)`), which is also
    where the missing `SalesOrder` -> `PARTIALLY_SHIPPED/SHIPPED` transition belongs.
  - `PurchaseOrderServiceImpl.save` also builds the GRN and its lines, duplicating
    `GoodsReceiptNoteImpl.create(PurchaseOrder)`; likewise `SalesOrderServiceImpl.save` builds the picking
    job. Delegate to the owning service.
  - `Bootstrap/Bootstrap.java` (400+ lines) seeds every aggregate; acceptable for seed code, not a target.
  - `PurchaseOrderRepository`/`SalesOrderRepository`/`StockRepository` mix CRUD with reporting queries
    (9-12 native queries each). Acceptable in Spring practice; mention only.

### OCP

- Good: `SuggestionServiceImpl` - adding a suggestion type is a new enum constant + one indexing loop.
- Violation: `StockAdjustmentServiceImpl.apply` `switch`. Strategy fix in Section 10 item 3.

### LSP

- Only framework subclasses exist, and `TwoFactorAuthenticationSuccessHandler` honours the superclass
  contract (it either redirects to 2FA or delegates to `super`). After introducing `AbstractOrder`, LSP is
  what makes `isModifiable()` / `getTotal()` callable on any order; add a test that iterates a
  `List<AbstractOrder<?>>` to show substitutability.

### ISP

- Good: `EmailService` (2 methods), `SuggestionService` (1 method + nested types), `StockAdjustmentService`.
- Weak: `PurchaseOrderService` (17 methods) and `SalesOrderService` (18) mix CRUD, advanced search and
  dashboard KPIs (`findOpenPurchaseOrdersKpi`, `findTopVendorsBySpendYtd`, `findDailySalesLast30Days`...).
  `DashBoardController`/`ReportController` are the only KPI consumers. Smallest fix: extract
  `PurchaseOrderReportingService` / `SalesOrderReportingService` interfaces that the existing impls also
  implement (one class, two interfaces - no behaviour change), and make the dashboard depend on the narrow
  interface. Optional; lower priority than Sections 3/7.
- `PurchaseOrderService.findByPurchaseOrderNumber` is backed by a native query on a non-existent column
  `purchase_order.purchase_order_number` (`PurchaseOrderRepository` line 36) and has no callers - dead,
  broken API; remove it from the interface.

### DIP

- Genuine: all controllers and services use constructor injection of interfaces; `EmailServiceImpl` depends
  on `JavaMailSender` (abstraction) and `@Value` config.
- Violations: `SearchController` injects `ItemRepository`, `CustomerRepository`, `VendorRepository`
  directly (bypassing the service layer); `PickingJobServiceImpl` and `PurchaseOrderServiceImpl` depend on
  6 repositories each (including other aggregates' repositories). Minor; fix only `SearchController` if
  time permits.

---

## 9. Design patterns

| Pattern | Where | Genuine? |
|---|---|---|
| Layered architecture / MVC + Front Controller | `Controllers` -> `Services` -> `Repositories`, Thymeleaf views, Spring `DispatcherServlet` | yes |
| Repository | `Repositories/*` (Spring Data) | yes (framework-provided) |
| Service Layer + interface/impl | `Services/*Service` + `*ServiceImpl` | yes |
| DTO | `Domains/DTOs/*` (e.g. `PickingJobDto`, `GoodsReceiptNoteDto`, KPI projections) | yes |
| Builder | Lombok `@Builder` on every entity/DTO | yes but generated; mention as such |
| Specification (Query Object) | `buildSpec(...)` in PO/SO/PickingJob/GRN services | yes, hand-written |
| Template Method | `TwoFactorAuthenticationSuccessHandler` over `SimpleUrlAuthenticationSuccessHandler`; Spring `JdbcTemplate`-style infra | yes (framework); domain version added by `AbstractOrder.getTotal()` |
| Facade | `SuggestionIndex` hides `Trie` + side maps; `SuggestionServiceImpl` hides five repositories | yes |
| Registry | `SuggestionServiceImpl.indexes` (`EnumMap<Type, SuggestionIndex>`) | yes |
| Static factory / seed pools | `StatePool.getStates()`, `ItemPool`, `VendorPool`, `CustomerPool` (private constructors) | partially - these are constant holders, not the GoF Object Pool; do not label them "Object Pool" |
| Singleton | Spring-managed beans (default scope) | framework-provided |
| Dependency Injection / IoC | constructor injection everywhere | yes |
| Advice / Interceptor (AOP) | `GlobalExceptionHandler`, `RequestContextAdvice` (`@ControllerAdvice`), `@Transactional` proxies | yes (framework) |
| Strategy | **missing** - `StockAdjustmentServiceImpl.apply` switch | add (Section 10 item 3) |
| State | **missing** - order/GRN/picking status transitions | add lightweight version (Section 10 item 2) |

---

## 10. Recommended minimal change set (priority order)

Each item lists schema impact and required tests. None needs a new dependency. Only item 6 (optional)
could need a migration; the recommended path needs **none**.

### 1. Lombok hygiene on entities (encapsulation, correctness) - no schema change

- In `PurchaseOrder`, `SalesOrder`, `GoodsReceiptNote`, `PickingJob`: replace `@Data` with
  `@Getter @Setter`; add `@Setter(AccessLevel.NONE)` on `id` and `status`.
- On all entities with bidirectional collections: exclude collections/back-references from
  `toString`/`equals`/`hashCode` (`@ToString.Exclude`, `@EqualsAndHashCode.Exclude`, or id-only equality).
- Tests: a unit test proving `toString()`/`hashCode()` on a `SalesOrder` with a line does not recurse;
  existing 32 tests must still pass (`Bootstrap` and repository tests use builders, unaffected).

### 2. Domain-owned state transitions (encapsulation, lightweight State) - no schema change

Add methods that guard legal transitions and throw `IllegalStateException` (or a domain exception from
item 5) on illegal ones:

- `PurchaseOrder.registerReceipt(boolean allLinesReceived)` -> `PARTIALLY_RECEIVED` or `RECEIVED`;
  `GoodsReceiptNote.markFulfilled()`; `PickingJob.markFulfilled()`;
  `SalesOrder.registerShipment(boolean hasOpenBackorders)` -> `PARTIALLY_SHIPPED` or `SHIPPED`;
  `isModifiable()` on both orders (from item 4).
- Replace raw `setStatus` calls in `GoodsReceiptNoteImpl.fulfill`, `PickingJobServiceImpl.fulfill`,
  `SalesOrderServiceImpl.updateById`, `Bootstrap` line 404.
- This **fixes the partial-receipt overwrite bug** (`GoodsReceiptNoteImpl` lines 132-135) and lets the
  fulfilment flow finally mark sales orders shipped (shipment tracking requirement).
- Keep enum constant order unchanged (ordinal persistence + native queries).
- Tests: pure unit tests of each transition (legal and illegal), and a service test that a partial GRN
  leaves the PO `PARTIALLY_RECEIVED`.

### 3. Strategy for stock adjustments (runtime polymorphism, OCP) - no schema change

- `Services/adjustment/StockAdjustmentHandler` interface: `AdjustmentType type(); void apply(StockAdjustment a);`
  (or keep it in `Services/` to respect the flat package layout - preferred).
- Four `@Component` implementations: `CycleCountHandler`, `WriteOffHandler`, `DamageHandler`,
  `TransferHandler` (damage and transfer may share an abstract `MoveStockHandler` base - a second,
  genuine inheritance example). Move the existing private helpers into a small shared collaborator
  (e.g. `StockLedger` with `setQtyAt/decrementAt/incrementAt`) so nothing is duplicated.
- `StockAdjustmentServiceImpl` receives `List<StockAdjustmentHandler>`, builds an
  `EnumMap<AdjustmentType, StockAdjustmentHandler>`, and `apply` becomes a lookup + dispatch.
  Fail fast at startup if a type has no handler.
- Tests: one unit test per handler (Mockito is in `spring-boot-starter-test`, already available offline),
  plus a test that every `AdjustmentType` has a handler.

### 4. `AbstractOrder` `@MappedSuperclass` + `OrderLine` interface (inheritance, abstraction, Template Method, generics) - no schema change

- As designed in Section 7: superclass holds `date` + `getTotal()` + abstract `getLines()` / `isModifiable()`;
  `@SuperBuilder` on `AbstractOrder`, `PurchaseOrder`, `SalesOrder`; keep `@Id` in subclasses.
- Replace the duplicated modifiable checks in `PurchaseOrderServiceImpl.updateById`,
  `SalesOrderServiceImpl.updateById`, `SalesOrderController` lines 272 and 323 with `isModifiable()`
  (line 272 must use the persisted `order`, not the bound form object - fixes a dead guard).
- Tests: polymorphic test over `List<AbstractOrder<?>>` asserting `getTotal()` and `isModifiable()`;
  run `WarehouseManagementApplicationTests` to prove `ddl-auto=validate` still passes; existing repository
  tests prove builders still work.

### 5. Exception hierarchy (inheritance, exception handling) - no schema change

- `Domains/Exceptions/WarehouseException extends Exception` (checked, to keep all existing `throws`
  clauses valid);
  `EntityNotFoundException extends WarehouseException` <- `CustomerNotFoundException`,
  `VendorNotFoundException`, `ItemNotFoundException`, `ItemPriceNotFoundException`,
  `PurchaseOrderNotFoundException`, `SalesOrderNotFoundException`;
  `OrderModificationException extends WarehouseException` <- `ReceivedOrderModificationException`,
  `ShippedOrderModificationException`.
  Leave the three `RuntimeException`s (user/auth) alone - they have different handling in
  `AdminUserController`. Name the base `EntityNotFoundException` something else (e.g.
  `ResourceNotFoundException`) to avoid clashing with `jakarta.persistence.EntityNotFoundException`.
- Keep all existing `@ExceptionHandler` methods (most-specific wins), and optionally add one fallback for
  `WarehouseException` in `GlobalExceptionHandler`.
- Tests: a `@WebMvcTest`-style or plain unit test of `GlobalExceptionHandler` mappings; `instanceof`
  hierarchy test is cheap.

### 6. `@Embeddable Address` for `Customer` / `Vendor` (composition, DRY) - no schema change if columns keep names

- Fields `street, city, state, zipcode` move into `Address` with `format()`; `Customer`/`Vendor` keep
  delegating `formattedAddress()` and, to avoid template churn, delegating `getStreet()`-style accessors
  or the templates are updated in the same change.
- Must update `Specification`s / JPQL that reference `city`, `state`, etc. (grep `CustomerServiceImpl`,
  `VendorServiceImpl`, repositories, `*Pool`, `Bootstrap`, templates before implementing).
- Lower priority: more touch points than items 1-5. Do it only after items 1-5 are green.

### 7. Cleanups that support the OOM narrative (no behaviour change)

- Delete `Services/GoodsReceiptNoteImplBack.java` (fully commented out; false polymorphism signal).
- Remove the broken `findByPurchaseOrderNumber` from `PurchaseOrderService`, impl and repository.
- Remove `PoStatus.pk` (unused, misleading) or document that persistence is ordinal; fix `SoStatus`
  comments (`//1`, `//2`).
- Add `@Override` to `Counter.toString()`.
- Optional: move the picking-job fulfilment workflow from `PickingJobController` into one transactional
  service method (SRP + transaction integrity), and narrow `SearchController` to services (DIP).

### Explicitly **not** recommended

- JPA `@Inheritance` tables for orders or parties (schema + native-query rewrites, no gain).
- A universal `BaseEntity` superclass for all entities (cosmetic, high builder churn).
- Converting status enums to `EnumType.STRING` (needs migration + rewrite of 30+ native queries).
- Adding a `Shipment` entity just for the assignment: outbound shipment is already represented by
  `PickingJob` (+ `Invoice`, `Backorder`) and inbound by `GoodsReceiptNote`. Completing the status
  transitions (item 2) gives real shipment tracking; a new table would need a migration and new UI.
- Second implementations of service interfaces created only to "show polymorphism" (mock/fake impls).

---

## 11. Incidental defects found during the audit (relevant to the demo)

These are correctness issues an examiner running the app could hit; they are listed so the change set can
address them alongside the OOM work.

1. `Services/GoodsReceiptNoteImpl.fulfill` lines 132-135: `PARTIALLY_RECEIVED` is immediately overwritten
   by `RECEIVED` (missing `else`). Fixed by item 2.
2. `Services/PickingJobServiceImpl.fulfill` lines 158-180: `pickingJob.getPickingJobLines().remove(i)`
   inside an index-based `for` loop shifts the list, so the next line is skipped and the DTO line index
   (`pickingJobDto.getPickingJobDtoLines().get(i)`) no longer matches the job line. Iterate over a copy or
   collect removals and apply afterwards.
3. The fulfilment flow never sets `SalesOrder.status` to `PARTIALLY_SHIPPED` / `SHIPPED` (Section 3). Fixed
   by item 2 + the SRP move in item 7.
4. `Controllers/SalesOrderController.removeSaleOrderLineFromUpdateOrderForm` line 272 checks the status of
   the form-bound object (always default `PENDING`), so shipped orders can have lines removed.
5. `Controllers/SalesOrderController.deleteSalesOrder` lines 323-330: if the order is absent,
   `order.get()` in the `else if` throws `NoSuchElementException` before reaching the `notFound` branch.
6. `PurchaseOrderServiceImpl.updateById` / `SalesOrderServiceImpl.updateById`: `Long` ids compared with
   `!=` (reference equality). With sequence increment 50, ids above 127 are common, so the comparison is
   "always different" - currently harmless (re-sets the same item) but wrong; use `Objects.equals`. Both
   methods also call `findById(id)` twice.
7. `@Data` recursion risk on bidirectional entities (Section 3).
8. `PurchaseOrderRepository.findByPurchaseOrderNumber` queries a non-existent column (dead code).
9. `Services/InvoiceServiceImpl.createByPickingJob` uses `System.out.printf` (use a logger).

---

## 12. Mapping to the assignment checklist after the change set

| Requirement | Demonstrated by (after items 1-5) |
|---|---|
| Classes / objects / attributes / methods / constructors | Entities + domain methods (`registerReceipt`, `registerShipment`, `isModifiable`, `getTotal`), DI constructors, overloaded exception ctors |
| Encapsulation | Read-only `id`/`status`; transitions only via domain methods; private helpers in handlers and `SuggestionIndex`/DSA |
| Abstraction | `AbstractOrder`, `OrderLine`, `StockAdjustmentHandler`, service interfaces |
| Inheritance | `PurchaseOrder`/`SalesOrder extends AbstractOrder`; exception hierarchy; optional `MoveStockHandler` base |
| Compile-time polymorphism | Overloads (`findAll`, ctors), bounded generics `AbstractOrder<L extends OrderLine>`, `BinarySearchTree<T extends Comparable<T>>` |
| Runtime polymorphism | `AbstractOrder.isModifiable()`/`getLines()` overrides; `StockAdjustmentHandler` dispatch; Spring handler override |
| Association / aggregation / composition | `@ManyToOne` associations; `Vendor`-`Item`, `WarehouseSection`-`Stock` aggregation; order/lines composition with root-controlled `addLine`; optional `@Embeddable Address` |
| SOLID | SRP (fulfilment service), OCP + DIP (strategy map), LSP (order substitution test), ISP (optional reporting interfaces) |
| Design patterns | Strategy, Template Method, lightweight State (guarded transitions), Repository, Service Layer, DTO, Builder, Specification, Facade, Registry, DI |
| Exception handling | Exception hierarchy + `GlobalExceptionHandler` most-specific mapping |
