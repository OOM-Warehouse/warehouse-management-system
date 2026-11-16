package com.example.warehouseManagement.Domains.Exceptions;

/** Thrown when a goods receipt note cannot be found. */
public class GoodsReceiptNoteNotFoundException extends RuntimeException {
    public GoodsReceiptNoteNotFoundException(String message) {
        super(message);
    }

    public GoodsReceiptNoteNotFoundException() {
        super("Goods receipt note not found.");
    }
}
