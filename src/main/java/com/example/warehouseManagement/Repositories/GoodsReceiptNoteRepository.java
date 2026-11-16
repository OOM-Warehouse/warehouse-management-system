package com.example.warehouseManagement.Repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.warehouseManagement.Domains.GoodsReceiptNote;
import com.example.warehouseManagement.Domains.GoodsReceiptNote.GrnStatus;

public interface GoodsReceiptNoteRepository
        extends JpaRepository<GoodsReceiptNote, Long>,
                JpaSpecificationExecutor<GoodsReceiptNote> {

    long countByStatus(GrnStatus status);

    /** Oldest still-PENDING GRN of a purchase order (at most one is kept open by design). */
    Optional<GoodsReceiptNote> findFirstByPurchaseOrderIdAndStatusOrderByIdAsc(Long purchaseOrderId, GrnStatus status);

    /** Every GRN (pending and fulfilled) raised for a purchase order. */
    List<GoodsReceiptNote> findAllByPurchaseOrderId(Long purchaseOrderId);


    @Query(value = """
        SELECT
            grn.date AS "date",
            purchase_order.id AS "purchaseOrder",
            grn.id AS "goodsReceiptNote"
        FROM
            goods_receipt_note grn
            INNER JOIN purchase_order ON purchase_order.id = grn.purchase_order_id
            INNER JOIN vendor ON purchase_order.vendor_id = vendor.id
        WHERE
            purchase_order.vendor_id = :vendorId
            AND purchase_order.status = 0
        """, nativeQuery = true)
    List<GoodsReceiptNote> findAllPendingByVendor(@Param("vendorId") Long vendorId);
}
