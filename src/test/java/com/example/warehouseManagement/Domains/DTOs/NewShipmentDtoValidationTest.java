package com.example.warehouseManagement.Domains.DTOs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class NewShipmentDtoValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private NewShipmentDto valid() {
        NewShipmentDto dto = new NewShipmentDto();
        dto.setPickingJobId(1L);
        dto.setCarrier("DHL");
        return dto;
    }

    @Test
    void validDtoHasNoViolations() {
        assertThat(validator.validate(valid())).isEmpty();
    }

    @Test
    void blankCarrierAndMissingJobAreRejected() {
        NewShipmentDto dto = valid();
        dto.setCarrier(" ");
        dto.setPickingJobId(null);
        assertThat(validator.validate(dto)).extracting(c -> c.getPropertyPath().toString())
                .containsExactlyInAnyOrder("carrier", "pickingJobId");
    }

    @Test
    void pastDeliveryDateAndOverlongTrackingNumberAreRejected() {
        NewShipmentDto dto = valid();
        dto.setExpectedDeliveryDate(LocalDate.now().minusDays(1));
        dto.setTrackingNumber("X".repeat(33));
        assertThat(validator.validate(dto)).extracting(c -> c.getPropertyPath().toString())
                .containsExactlyInAnyOrder("expectedDeliveryDate", "trackingNumber");
    }
}
