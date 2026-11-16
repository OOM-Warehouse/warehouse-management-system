package com.example.warehouseManagement.Domains;

import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Abstraction shared by {@link PurchaseOrder} and {@link SalesOrder}.
 *
 * It is a {@code @MappedSuperclass}: the shared persistent state (the order date) is inherited by both
 * tables without introducing a new table or changing the schema. The ids stay in the subclasses so each
 * entity keeps its own id sequence.
 *
 * {@link #getTotal()} is a template method: the algorithm (sum of line subtotals) lives here, while the
 * lines themselves, the editability rule, the label and the order type are supplied by each subclass
 * (runtime polymorphism).
 *
 * @param <L> the kind of line the order is made of
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@MappedSuperclass
public abstract class AbstractOrder<L extends OrderLine> {

    @Column(name = "date")
    @lombok.Builder.Default
    private LocalDate date = LocalDate.now();

    /** Identifier of the order (declared here so heterogeneous lists can be reported on). */
    public abstract Long getId();

    /** The lines that compose this order (the order owns them: composition). */
    public abstract List<L> getLines();

    /** True while the order may still be edited or deleted (each order type has its own rule). */
    public abstract boolean isModifiable();

    /** Human readable status for reports. */
    public abstract String getStatusLabel();

    /** The business partner on the other side of the order: the vendor or the customer. */
    public abstract Party getCounterparty();

    /** Human readable order kind, for example "Purchase Order". */
    public abstract String getOrderType();

    /** Template method: total value = sum of the subtotals of every line. */
    public double getTotal() {
        double total = 0;
        for (L line : getLines()) {
            total += line.getSubtotal();
        }
        return total;
    }

    /** Total quantity of units across all lines. */
    public int getTotalQty() {
        int qty = 0;
        for (L line : getLines()) {
            qty += line.getQty();
        }
        return qty;
    }
}
