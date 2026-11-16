package com.example.warehouseManagement.Domains;

import java.time.LocalDate;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@Entity
@Table(name = "goods_receipt_note")
public class GoodsReceiptNote {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id")
    @Setter(AccessLevel.NONE)
    private Long id;
    @ManyToOne
    @JoinColumn(name = "purchase_order_id") //Foreign key to purchase order
    private PurchaseOrder purchaseOrder;
    @OneToMany(mappedBy = "goodsReceiptNote", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Column(name = "goods_receipt_note_lines")
    @Builder.Default
    private List<GoodsReceiptNoteLine> goodsReceiptNoteLines = new ArrayList<>();
    @Column(name = "date")
    @Builder.Default
    private LocalDate date = LocalDate.now();
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private GrnStatus status = GrnStatus.PENDING;
    /** Marks the note as received. A fulfilled note is final. */
    public void markFulfilled() {
        if (status == GrnStatus.FULFILLED) {
            throw new IllegalStateException("Goods receipt note " + id + " is already fulfilled");
        }
        this.status = GrnStatus.FULFILLED;
    }

    /** Adds a line and keeps both sides of the association in sync. */
    public void addLine(GoodsReceiptNoteLine line) {
        if (line == null) {
            throw new IllegalArgumentException("Line must not be null");
        }
        line.setGoodsReceiptNote(this);
        goodsReceiptNoteLines.add(line);
    }

    public int getTotalQty() {
        int total = 0;
        for (GoodsReceiptNoteLine goodsReceiptNoteLine : goodsReceiptNoteLines) 
            total += goodsReceiptNoteLine.getQty();
        return total;
    }

    public enum GrnStatus {
        PENDING, //0
        FULFILLED //1
    }

}