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
 * An order placed with a {@link Vendor}. Extends {@link AbstractOrder} (inheritance): the date, the total
 * and the line contract are shared with {@link SalesOrder}.
 *
 * Encapsulation: the id, the status and the collections are only changed through domain methods
 * ({@link #markPartiallyReceived()}, {@link #markReceived()}, {@link #addLine(PurchaseOrderLine)}).
 */
@NoArgsConstructor
@Getter
@Setter
@SuperBuilder
@Entity
@Table(name = "purchase_order")
public class PurchaseOrder extends AbstractOrder<PurchaseOrderLine> {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id")
    @Setter(AccessLevel.NONE)
    private Long id;
    @ManyToOne
    @JoinColumn(name = "vendor_id")
    private Vendor vendor;
    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private List<GoodsReceiptNote> goodsReceiptNotes = new ArrayList<>();
    // Composition: a line cannot exist without its purchase order
    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private List<PurchaseOrderLine> purchaseOrderLines = new ArrayList<>();
    @Column(name = "status")
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private PoStatus status = PoStatus.IN_TRANSIT;

    // ---------------------------------------------------------------- AbstractOrder contract

    @Override
    public List<PurchaseOrderLine> getLines() {
        return purchaseOrderLines;
    }

    /** A purchase order can be edited until it has been fully received. */
    @Override
    public boolean isModifiable() {
        return status != PoStatus.RECEIVED;
    }

    @Override
    public String getStatusLabel() {
        return status.name();
    }

    @Override
    public Party getCounterparty() {
        return vendor;
    }

    @Override
    public String getOrderType() {
        return "Purchase Order";
    }

    // ---------------------------------------------------------------- domain behaviour

    /** True while goods can still be received against this order. */
    public boolean isReceivable() {
        return status != PoStatus.RECEIVED;
    }

    /** Some, but not all, of the ordered goods have arrived. */
    public void markPartiallyReceived() {
        requireReceivable("partially received");
        this.status = PoStatus.PARTIALLY_RECEIVED;
    }

    /** All the ordered goods have arrived. A received order is final. */
    public void markReceived() {
        requireReceivable("received");
        this.status = PoStatus.RECEIVED;
    }

    private void requireReceivable(String target) {
        if (status == PoStatus.RECEIVED) {
            throw new IllegalStateException("Purchase order " + id + " is already received, it cannot be marked as " + target);
        }
    }

    /** Adds a line and keeps both sides of the association in sync. */
    public void addLine(PurchaseOrderLine line) {
        if (line == null) {
            throw new IllegalArgumentException("Line must not be null");
        }
        line.setPurchaseOrder(this);
        purchaseOrderLines.add(line);
    }

    /** Creates and adds a line for {@code qty} units of {@code item} at the given cost. */
    public PurchaseOrderLine addLine(Item item, int qty, ItemCost cost) {
        if (qty < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative");
        }
        PurchaseOrderLine line = PurchaseOrderLine.builder().item(item).qty(qty).itemCost(cost).build();
        addLine(line);
        return line;
    }

    /** Removes a line; orphan removal deletes it from the database. */
    public void removeLine(PurchaseOrderLine line) {
        if (purchaseOrderLines.remove(line)) {
            line.setPurchaseOrder(null);
        }
    }

    public enum PoStatus {
        IN_TRANSIT(0),
        RECEIVED(1),
        PARTIALLY_RECEIVED(2);

        private final int pk;

        PoStatus(int pk) {
            this.pk = pk;
        }

        public int getPk() {
            return pk;
        }
    }
}
