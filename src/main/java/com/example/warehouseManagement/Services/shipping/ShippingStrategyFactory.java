package com.example.warehouseManagement.Services.shipping;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.warehouseManagement.Domains.ShippingMethod;

/** Factory: picks the {@link ShippingStrategy} registered for a {@link ShippingMethod}. */
@Component
public class ShippingStrategyFactory {
    private final List<ShippingStrategy> strategies;

    public ShippingStrategyFactory(List<ShippingStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
    }

    public ShippingStrategy forMethod(ShippingMethod method) {
        if (method == null) {
            throw new IllegalArgumentException("Shipping method is required.");
        }
        return strategies.stream().filter(s -> s.supports(method)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No shipping strategy for " + method));
    }
}
