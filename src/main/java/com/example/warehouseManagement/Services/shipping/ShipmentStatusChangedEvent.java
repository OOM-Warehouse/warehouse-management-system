package com.example.warehouseManagement.Services.shipping;

import org.springframework.context.ApplicationEvent;

import com.example.warehouseManagement.Domains.Shipment;
import com.example.warehouseManagement.Domains.ShipmentStatus;

/** Observer pattern: published by the shipment service on every status change. */
public class ShipmentStatusChangedEvent extends ApplicationEvent {
    private final transient Shipment shipment;
    private final ShipmentStatus previous;
    private final ShipmentStatus current;

    public ShipmentStatusChangedEvent(Object source, Shipment shipment, ShipmentStatus previous, ShipmentStatus current) {
        super(source);
        this.shipment = shipment;
        this.previous = previous;
        this.current = current;
    }

    public Shipment getShipment() { return shipment; }
    public ShipmentStatus getPrevious() { return previous; }
    public ShipmentStatus getCurrent() { return current; }
}
