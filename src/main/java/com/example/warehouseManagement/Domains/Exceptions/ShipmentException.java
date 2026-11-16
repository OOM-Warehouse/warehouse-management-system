package com.example.warehouseManagement.Domains.Exceptions;

/** Base of the unchecked business-rule failures raised by the shipment module. */
public abstract class ShipmentException extends RuntimeException {
    protected ShipmentException(String message) {
        super(message);
    }
}
