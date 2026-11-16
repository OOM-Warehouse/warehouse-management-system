package com.example.warehouseManagement.Services.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.warehouseManagement.Domains.ShippingMethod;

class ShippingStrategyTest {
    private final ShippingStrategyFactory factory = new ShippingStrategyFactory(List.of(
            new StandardShippingStrategy(), new ExpressShippingStrategy(), new OvernightShippingStrategy()));

    @Test
    void strategiesGiveDifferentCostsAndDates() {
        LocalDate day = LocalDate.of(2025, 1, 10);
        ShippingStrategy std = factory.forMethod(ShippingMethod.STANDARD);
        ShippingStrategy exp = factory.forMethod(ShippingMethod.EXPRESS);
        ShippingStrategy ovn = factory.forMethod(ShippingMethod.OVERNIGHT);

        assertThat(std.calculateCost(10)).isEqualTo(10.0);
        assertThat(exp.calculateCost(10)).isEqualTo(25.0);
        assertThat(ovn.calculateCost(10)).isEqualTo(50.0);
        assertThat(std.estimateDeliveryDate(day)).isEqualTo(day.plusDays(5));
        assertThat(exp.estimateDeliveryDate(day)).isEqualTo(day.plusDays(2));
        assertThat(ovn.estimateDeliveryDate(day)).isEqualTo(day.plusDays(1));
    }

    @Test
    void factoryReturnsMatchingStrategyForEveryMethod() {
        for (ShippingMethod m : ShippingMethod.values()) {
            assertThat(factory.forMethod(m).supports(m)).isTrue();
        }
        assertThat(factory.forMethod(ShippingMethod.EXPRESS)).isInstanceOf(ExpressShippingStrategy.class);
    }

    @Test
    void factoryRejectsNullAndUnregisteredMethods() {
        assertThatThrownBy(() -> factory.forMethod(null)).isInstanceOf(IllegalArgumentException.class);
        ShippingStrategyFactory partial = new ShippingStrategyFactory(List.of(new StandardShippingStrategy()));
        assertThatThrownBy(() -> partial.forMethod(ShippingMethod.OVERNIGHT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
