package com.example.warehouseManagement.Domains.Exceptions;

public class PurchaseOrderNotFoundException extends ResourceNotFoundException {
    public PurchaseOrderNotFoundException(String message) {
        super(message);
    }

    public PurchaseOrderNotFoundException() {
        super("Purchase Order Not Found");
    }
}
