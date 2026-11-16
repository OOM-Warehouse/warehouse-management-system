package com.example.warehouseManagement.Domains.Exceptions;

/** The picking job / order cannot be shipped (not fulfilled, nothing picked, already shipped). */
public class ShipmentNotAllowedException extends ShipmentException {
    public ShipmentNotAllowedException(String message) {
        super(message);
    }
}
