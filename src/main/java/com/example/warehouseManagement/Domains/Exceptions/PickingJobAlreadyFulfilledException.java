package com.example.warehouseManagement.Domains.Exceptions;

/**
 * Thrown when a picking job that is not PENDING is fulfilled again. Guards
 * against duplicate stock deductions, invoices and backorders.
 */
public class PickingJobAlreadyFulfilledException extends RuntimeException {
    public PickingJobAlreadyFulfilledException(Long pickingJobId) {
        super("Picking job " + pickingJobId + " has already been fulfilled.");
    }
}
