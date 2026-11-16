package com.example.warehouseManagement.Services.shipping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Observer: logs every shipment status change (stand-in for a customer notification). */
@Component
public class ShipmentNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(ShipmentNotificationListener.class);

    @EventListener
    public void onStatusChanged(ShipmentStatusChangedEvent event) {
        log.info("Shipment {} moved {} -> {}", event.getShipment().getTrackingNumber(),
                event.getPrevious(), event.getCurrent());
    }
}
