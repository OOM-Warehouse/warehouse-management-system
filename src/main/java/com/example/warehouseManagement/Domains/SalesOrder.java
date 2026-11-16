package com.example.warehouseManagement.Domains;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * An order placed by a {@link Customer}. Extends {@link AbstractOrder} (inheritance).
 *
 * Encapsulation: the id, the status and the collections are only changed through domain methods
 * ({@link #markPartiallyShipped()}, {@link #markShipped()}, {@link #addLine(SalesOrderLine)}).
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name="sales_order")
public class SalesOrder extends AbstractOrder<SalesOrderLine> {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id")
    @Setter(AccessLevel.NONE)
    private Long id;
    @ManyToOne
    @JoinColumn(name = "customer_id") //Foreing key to customer
    private Customer customer;
    // Association: picking jobs, invoices and backorders are documents that refer back to the order
    @OneToMany(mappedBy = "salesOrder", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private List<PickingJob> pickingJobs = new ArrayList<>();
    // Composition: a line cannot exist without its sales order
    @OneToMany(mappedBy = "salesOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private List<SalesOrderLine> saleOrderLines = new ArrayList<>();
    @OneToMany(mappedBy = "salesOrder", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private List<Invoice> invoices = new ArrayList<>();
    @OneToMany(mappedBy = "salesOrder", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private List<Backorder> backorders = new ArrayList<>();
    @Column(name = "status")
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private SoStatus status = SoStatus.PENDING;

    // ---------------------------------------------------------------- AbstractOrder contract

    @Override
    public List<SalesOrderLine> getLines() {
        return saleOrderLines;
    }

    /** A sales order can be edited until it has been shipped. */
    @Override
    public boolean isModifiable() {
        return status != SoStatus.SHIPPED;
    }

    @Override
    public String getStatusLabel() {
        return status.name();
    }

    @Override
    public Party getCounterparty() {
        return customer;
    }

    @Override
    public String getOrderType() {
        return "Sales Order";
    }

    // ---------------------------------------------------------------- domain behaviour

    /** Part of the order has been shipped (the rest is on backorder). Not allowed once shipped. */
    public void markPartiallyShipped() {
        requireNotShipped("partially shipped");
        this.status = SoStatus.PARTIALLY_SHIPPED;
    }

    /** The whole order has been shipped. A shipped order is final. */
    public void markShipped() {
        requireNotShipped("shipped");
        this.status = SoStatus.SHIPPED;
    }

    private void requireNotShipped(String target) {
        if (status == SoStatus.SHIPPED) {
            throw new IllegalStateException("Sales order " + id + " is already shipped, it cannot be marked as " + target);
        }
    }

    /** Adds a line and keeps both sides of the association in sync. */
    public void addLine(SalesOrderLine line) {
        if (line == null) {
            throw new IllegalArgumentException("Line must not be null");
        }
        line.setSalesOrder(this);
        saleOrderLines.add(line);
    }

    /** Creates and adds a line for {@code qty} units of {@code item} at the given price. */
    public SalesOrderLine addLine(Item item, int qty, ItemPrice price) {
        if (qty < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative");
        }
        SalesOrderLine line = SalesOrderLine.builder().item(item).qty(qty).itemPrice(price).build();
        addLine(line);
        return line;
    }

    /** Removes a line; orphan removal deletes it from the database. */
    public void removeLine(SalesOrderLine line) {
        if (saleOrderLines.remove(line)) {
            line.setSalesOrder(null);
        }
    }

    public enum SoStatus {
        PENDING, //0
        PARTIALLY_SHIPPED, //2
        SHIPPED //3
    }
}
