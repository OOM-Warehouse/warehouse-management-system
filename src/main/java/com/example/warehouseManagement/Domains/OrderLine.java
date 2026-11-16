package com.example.warehouseManagement.Domains;

/**
 * Abstraction over a single line of an order (purchase or sales).
 * Lets callers compute and display lines without knowing which kind of order owns them.
 */
public interface OrderLine {

    Item getItem();

    int getQty();

    /** Monetary value of the line (quantity multiplied by the unit cost or price). */
    double getSubtotal();
}
