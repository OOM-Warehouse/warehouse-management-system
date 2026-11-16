package com.example.warehouseManagement.Domains.Exceptions;

/**
 * Thrown when the quantities posted on a goods receipt are not acceptable:
 * negative, mismatching the receipt lines, or exceeding what was ordered.
 * Unchecked so that a surrounding {@code @Transactional} rolls back.
 */
public class InvalidReceiptQuantityException extends RuntimeException {
    public InvalidReceiptQuantityException(String message) {
        super(message);
    }
}
