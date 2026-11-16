package com.example.warehouseManagement.Domains.Exceptions;

/** Bad shipment input: missing carrier, delivery before dispatch, past expected date, ... */
public class InvalidShipmentDataException extends ShipmentException {
    public InvalidShipmentDataException(String message) {
        super(message);
    }
}
