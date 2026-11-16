package com.example.warehouseManagement.Services.shipping;

import java.time.LocalDate;

import com.example.warehouseManagement.Domains.ShippingMethod;

/** Strategy: one pricing and lead-time policy per {@link ShippingMethod}. */
public interface ShippingStrategy {
    boolean supports(ShippingMethod method);

    /** Cost of shipping the given number of units. */
    double calculateCost(int units);

    /** Expected delivery date for a parcel dispatched on the given date. */
    LocalDate estimateDeliveryDate(LocalDate dispatchDate);
}
