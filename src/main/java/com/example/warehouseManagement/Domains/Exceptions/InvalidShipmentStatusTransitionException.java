package com.example.warehouseManagement.Domains.Exceptions;

/** The requested status change is not allowed by {@code ShipmentStatus}. */
public class InvalidShipmentStatusTransitionException extends ShipmentException {
    public InvalidShipmentStatusTransitionException(String message) {
        super(message);
    }
}
