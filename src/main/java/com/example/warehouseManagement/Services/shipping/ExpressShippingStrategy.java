package com.example.warehouseManagement.Services.shipping;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.example.warehouseManagement.Domains.ShippingMethod;

/** EXPRESS shipping: base 15.0 plus 1.0 per unit, delivered in 2 day(s). */
@Component
public class ExpressShippingStrategy implements ShippingStrategy {
    @Override
    public boolean supports(ShippingMethod method) {
        return method == ShippingMethod.EXPRESS;
    }

    @Override
    public double calculateCost(int units) {
        return 15.0 + 1.0 * Math.max(units, 0);
    }

    @Override
    public LocalDate estimateDeliveryDate(LocalDate dispatchDate) {
        return dispatchDate.plusDays(2);
    }
}
