package com.example.warehouseManagement.Domains.Exceptions;

/** Base type for "this order can no longer be modified" failures. */
public class OrderModificationException extends Exception {
    public OrderModificationException(String message) {
        super(message);
    }
}
