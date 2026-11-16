package com.example.warehouseManagement.Domains.Exceptions;

/**
 * Thrown when a pick request breaks a business rule: negative quantity,
 * quantity above what the line asks for, unknown bin, DTO that does not match
 * the picking job, nothing picked at all, or stock that would go negative.
 * Unchecked so that a surrounding {@code @Transactional} rolls back.
 */
public class InvalidPickException extends RuntimeException {
    public InvalidPickException(String message) {
        super(message);
    }
}
