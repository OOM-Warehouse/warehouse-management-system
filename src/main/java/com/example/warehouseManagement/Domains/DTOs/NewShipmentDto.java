package com.example.warehouseManagement.Domains.DTOs;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import com.example.warehouseManagement.Domains.ShippingMethod;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Form backing object for creating a shipment from a fulfilled picking job. */
@Data
@NoArgsConstructor
public class NewShipmentDto {
    @NotNull(message = "Picking job is required")
    private Long pickingJobId;
    @NotBlank(message = "Carrier is required")
    @Size(max = 64, message = "Carrier must be at most 64 characters")
    private String carrier;
    /** Optional; a tracking number is generated when blank. */
    @Size(max = 32, message = "Tracking number must be at most 32 characters")
    private String trackingNumber;
    @NotNull(message = "Shipping method is required")
    private ShippingMethod shippingMethod = ShippingMethod.STANDARD;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @FutureOrPresent(message = "Expected delivery date cannot be in the past")
    private LocalDate expectedDeliveryDate;
}
