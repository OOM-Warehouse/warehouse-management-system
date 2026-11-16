package com.example.warehouseManagement.Domains.Exceptions;

/** Base type for "entity not found" failures, so callers can handle them generically. */
public class ResourceNotFoundException extends Exception {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
