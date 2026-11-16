package com.example.warehouseManagement.Services.shipping;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.example.warehouseManagement.Domains.ShippingMethod;

/** STANDARD shipping: base 5.0 plus 0.5 per unit, delivered in 5 day(s). */
@Component
public class StandardShippingStrategy implements ShippingStrategy {
    @Override
    public boolean supports(ShippingMethod method) {
        return method == ShippingMethod.STANDARD;
    }

    @Override
    public double calculateCost(int units) {
        return 5.0 + 0.5 * Math.max(units, 0);
    }

    @Override
    public LocalDate estimateDeliveryDate(LocalDate dispatchDate) {
        return dispatchDate.plusDays(5);
    }
}
