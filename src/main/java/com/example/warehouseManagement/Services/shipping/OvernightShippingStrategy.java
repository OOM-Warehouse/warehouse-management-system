package com.example.warehouseManagement.Services.shipping;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.example.warehouseManagement.Domains.ShippingMethod;

/** OVERNIGHT shipping: base 30.0 plus 2.0 per unit, delivered in 1 day(s). */
@Component
public class OvernightShippingStrategy implements ShippingStrategy {
    @Override
    public boolean supports(ShippingMethod method) {
        return method == ShippingMethod.OVERNIGHT;
    }

    @Override
    public double calculateCost(int units) {
        return 30.0 + 2.0 * Math.max(units, 0);
    }

    @Override
    public LocalDate estimateDeliveryDate(LocalDate dispatchDate) {
        return dispatchDate.plusDays(1);
    }
}
