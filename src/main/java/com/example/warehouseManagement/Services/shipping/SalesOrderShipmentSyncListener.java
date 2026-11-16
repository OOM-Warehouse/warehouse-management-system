package com.example.warehouseManagement.Services.shipping;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.example.warehouseManagement.Domains.SalesOrder;
import com.example.warehouseManagement.Domains.SalesOrder.SoStatus;
import com.example.warehouseManagement.Domains.SalesOrderLine;
import com.example.warehouseManagement.Domains.Shipment;
import com.example.warehouseManagement.Repositories.SalesOrderRepository;
import com.example.warehouseManagement.Repositories.ShipmentRepository;

/**
 * Observer: keeps the sales order status in step with its shipments. The order only ever moves
 * forward (PENDING -> PARTIALLY_SHIPPED -> SHIPPED); a later return does not revert it.
 * Runs synchronously inside the publisher's transaction.
 */
@Component
public class SalesOrderShipmentSyncListener {
    private final ShipmentRepository shipmentRepository;
    private final SalesOrderRepository salesOrderRepository;

    public SalesOrderShipmentSyncListener(ShipmentRepository shipmentRepository,
                                          SalesOrderRepository salesOrderRepository) {
        this.shipmentRepository = shipmentRepository;
        this.salesOrderRepository = salesOrderRepository;
    }

    @EventListener
    public void onStatusChanged(ShipmentStatusChangedEvent event) {
        SalesOrder order = event.getShipment().getSalesOrder();
        if (order == null || order.getStatus() == SoStatus.SHIPPED) {
            return;
        }
        int shippedQty = 0;
        for (Shipment s : shipmentRepository.findBySalesOrderIdOrderByCreatedAtAsc(order.getId())) {
            if (s.getStatus().countsAsShipped()) {
                shippedQty += s.getTotalUnits();
            }
        }
        if (shippedQty == 0) {
            return;
        }
        int orderedQty = 0;
        for (SalesOrderLine line : order.getSaleOrderLines()) {
            orderedQty += line.getQty();
        }
        boolean complete = shippedQty >= orderedQty && order.getBackorders().isEmpty();
        SoStatus target = complete ? SoStatus.SHIPPED : SoStatus.PARTIALLY_SHIPPED;
        if (order.getStatus() != target) {
            if (complete) {
                order.markShipped();
            } else {
                order.markPartiallyShipped();
            }
            salesOrderRepository.save(order);
        }
    }
}
