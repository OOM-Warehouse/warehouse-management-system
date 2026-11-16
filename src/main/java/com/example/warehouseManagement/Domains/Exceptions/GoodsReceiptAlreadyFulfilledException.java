package com.example.warehouseManagement.Domains.Exceptions;

/**
 * Thrown when a goods receipt note that is already FULFILLED (or whose purchase
 * order is already RECEIVED) is processed again. Prevents inventory from being
 * increased twice by a repeated submission.
 */
public class GoodsReceiptAlreadyFulfilledException extends RuntimeException {
    public GoodsReceiptAlreadyFulfilledException(String message) {
        super(message);
    }

    public GoodsReceiptAlreadyFulfilledException() {
        super("This goods receipt has already been fulfilled and cannot be processed again.");
    }
}
