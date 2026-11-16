package com.example.warehouseManagement.Domains.Exceptions;

public class SalesOrderNotFoundException extends ResourceNotFoundException {
    public SalesOrderNotFoundException(String message) {
        super(message);
    }

    public SalesOrderNotFoundException() {
        super("Sales Order Not Found");
    }
}
