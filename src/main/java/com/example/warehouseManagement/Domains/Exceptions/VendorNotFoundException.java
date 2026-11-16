package com.example.warehouseManagement.Domains.Exceptions;

public class VendorNotFoundException extends ResourceNotFoundException {
    public VendorNotFoundException(String message) {
        super(message);
    }

    public VendorNotFoundException() {
        super("Vendor Not Found");
    }
}
