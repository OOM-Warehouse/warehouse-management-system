package com.example.warehouseManagement.Domains.Exceptions;

public class ItemNotFoundException extends ResourceNotFoundException {
    public ItemNotFoundException(String message) {
        super(message);
    }

    public ItemNotFoundException() {
        super("Item Not Found");
    }
}
