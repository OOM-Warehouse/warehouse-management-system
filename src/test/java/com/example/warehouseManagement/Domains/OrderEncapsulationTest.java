package com.example.warehouseManagement.Domains;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.example.warehouseManagement.Domains.PurchaseOrder.PoStatus;
import com.example.warehouseManagement.Domains.SalesOrder.SoStatus;

/** Business state of the entities is changed only through methods that validate it. */
class OrderEncapsulationTest {

    // ---------- Stock

    @Test
    void stock_increaseAndDecrease_changeQuantity() {
        Stock stock = Stock.builder().qtyOnHand(10).build();

        stock.increase(5);
        stock.decrease(3);

        assertThat(stock.getQtyOnHand()).isEqualTo(12);
    }

    @Test
    void stock_overloadsWithReason_behaveLikePlainVersions() {
        Stock stock = Stock.builder().qtyOnHand(10).build();

        stock.increase(2, "cycle count");
        stock.decrease(4, "damage");

        assertThat(stock.getQtyOnHand()).isEqualTo(8);
    }

    @Test
    void stock_cannotGoNegative() {
        Stock stock = Stock.builder().qtyOnHand(3).build();

        assertThatThrownBy(() -> stock.decrease(4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stock.decrease(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stock.increase(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stock.setCountedQty(-5)).isInstanceOf(IllegalArgumentException.class);
        assertThat(stock.getQtyOnHand()).isEqualTo(3);
    }

    @Test
    void stock_cycleCount_setsAbsoluteQuantity() {
        Stock stock = Stock.builder().qtyOnHand(3).build();

        stock.setCountedQty(40);

        assertThat(stock.getQtyOnHand()).isEqualTo(40);
    }

    // ---------- PurchaseOrder

    @Test
    void purchaseOrder_receiptLifecycle() {
        PurchaseOrder po = PurchaseOrder.builder().build();
        assertThat(po.getStatus()).isEqualTo(PoStatus.IN_TRANSIT);
        assertThat(po.isModifiable()).isTrue();

        po.markPartiallyReceived();
        assertThat(po.getStatus()).isEqualTo(PoStatus.PARTIALLY_RECEIVED);
        assertThat(po.isModifiable()).isTrue();

        po.markReceived();
        assertThat(po.getStatus()).isEqualTo(PoStatus.RECEIVED);
        assertThat(po.isModifiable()).isFalse();
    }

    @Test
    void purchaseOrder_receivedIsFinal() {
        PurchaseOrder po = PurchaseOrder.builder().status(PoStatus.RECEIVED).build();

        assertThatThrownBy(po::markReceived).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(po::markPartiallyReceived).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void purchaseOrder_addLine_linksBothSidesAndComputesTotal() {
        PurchaseOrder po = PurchaseOrder.builder().build();
        ItemCost cost = ItemCost.builder().cost(2.5).build();

        PurchaseOrderLine line = po.addLine(Item.builder().build(), 4, cost);

        assertThat(line.getPurchaseOrder()).isSameAs(po);
        assertThat(po.getPurchaseOrderLines()).containsExactly(line);
        assertThat(po.getTotal()).isEqualTo(10.0);
        assertThatThrownBy(() -> po.addLine(Item.builder().build(), -1, cost))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- SalesOrder

    @Test
    void salesOrder_shippingLifecycle() {
        SalesOrder so = SalesOrder.builder().build();
        assertThat(so.getStatus()).isEqualTo(SoStatus.PENDING);

        so.markPartiallyShipped();
        assertThat(so.getStatus()).isEqualTo(SoStatus.PARTIALLY_SHIPPED);
        assertThat(so.isModifiable()).isTrue();

        so.markShipped();
        assertThat(so.getStatus()).isEqualTo(SoStatus.SHIPPED);
        assertThat(so.isModifiable()).isFalse();
    }

    @Test
    void salesOrder_shippedIsFinal() {
        SalesOrder so = SalesOrder.builder().status(SoStatus.SHIPPED).build();

        assertThatThrownBy(so::markShipped).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(so::markPartiallyShipped).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void salesOrder_addLine_thenRemoveLine() {
        SalesOrder so = SalesOrder.builder().build();
        ItemPrice price = ItemPrice.builder().price(10.0).build();

        SalesOrderLine line = so.addLine(Item.builder().build(), 3, price);
        assertThat(line.getSalesOrder()).isSameAs(so);
        assertThat(so.getTotal()).isEqualTo(30.0);

        so.removeLine(line);
        assertThat(so.getSaleOrderLines()).isEmpty();
        assertThat(line.getSalesOrder()).isNull();
    }

    // ---------- GoodsReceiptNote / PickingJob

    @Test
    void goodsReceiptNote_fulfilledIsFinal() {
        GoodsReceiptNote grn = GoodsReceiptNote.builder().build();

        grn.markFulfilled();

        assertThat(grn.getStatus()).isEqualTo(GoodsReceiptNote.GrnStatus.FULFILLED);
        assertThatThrownBy(grn::markFulfilled).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pickingJob_fulfilledIsFinal() {
        PickingJob job = PickingJob.builder().build();

        job.markFulfilled();

        assertThat(job.getStatus()).isEqualTo(PickingJob.PjStatus.FULFILLED);
        assertThatThrownBy(job::markFulfilled).isInstanceOf(IllegalStateException.class);
    }
}
