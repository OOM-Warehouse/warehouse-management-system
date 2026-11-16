package com.example.warehouseManagement.Domains.Exceptions;

/** Checked, following the existing *NotFoundException convention. */
public class ShipmentNotFoundException extends Exception {
    public ShipmentNotFoundException(String message) {
        super(message);
    }
}
