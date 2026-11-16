package com.example.warehouseManagement.Domains;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Slf4j
@Entity
@Table(name="stock")
public class Stock {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Setter(AccessLevel.NONE)
    private Long id;
    @ManyToOne
    @JoinColumn(name = "warehouse_section_id")
    private WarehouseSection warehouseSection;
    @ManyToOne
    @JoinColumn(name = "item_id")
    private Item item;
    @Column(name = "qty_on_hand")
    @Setter(AccessLevel.NONE)
    private int qtyOnHand;

    /** Adds {@code qty} units. Quantity cannot be negative (zero is a no-op). */
    public void increase(int qty) {
        if (qty < 0) {
            throw new IllegalArgumentException("Quantity to add cannot be negative");
        }
        this.qtyOnHand += qty;
    }

    /** Adds {@code qty} units and logs why (compile-time polymorphism: overload of {@link #increase(int)}). */
    public void increase(int qty, String reason) {
        increase(qty);
        log.info("Stock {} increased by {} ({}), now {}", id, qty, reason, qtyOnHand);
    }

    /** Removes {@code qty} units. Quantity cannot be negative and cannot exceed what is on hand. */
    public void decrease(int qty) {
        if (qty < 0) {
            throw new IllegalArgumentException("Quantity to remove cannot be negative");
        }
        if (qty > qtyOnHand) {
            throw new IllegalArgumentException("Cannot remove " + qty + " units, only " + qtyOnHand + " on hand");
        }
        this.qtyOnHand -= qty;
    }

    /** Removes {@code qty} units and logs why (overload of {@link #decrease(int)}). */
    public void decrease(int qty, String reason) {
        decrease(qty);
        log.info("Stock {} decreased by {} ({}), now {}", id, qty, reason, qtyOnHand);
    }

    /** Sets the on-hand quantity to an absolute counted value (cycle count). Cannot be negative. */
    public void setCountedQty(int counted) {
        if (counted < 0) {
            throw new IllegalArgumentException("Counted quantity cannot be negative");
        }
        this.qtyOnHand = counted;
    }
}
