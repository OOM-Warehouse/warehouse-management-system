package com.example.warehouseManagement.Domains.Exceptions;

public class ItemPriceNotFoundException extends ResourceNotFoundException {
    public ItemPriceNotFoundException(String message) {
        super(message);
    }

    public ItemPriceNotFoundException() {
        super("Item Price Not Found");
    }
}
