package com.example.warehouseManagement.Domains.Exceptions;

/** Another shipment already uses this tracking number. */
public class DuplicateTrackingNumberException extends ShipmentException {
    public DuplicateTrackingNumberException(String message) {
        super(message);
    }
}
