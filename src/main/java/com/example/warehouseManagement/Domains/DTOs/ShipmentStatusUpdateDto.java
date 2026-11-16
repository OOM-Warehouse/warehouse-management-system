package com.example.warehouseManagement.Domains.DTOs;

import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;

import com.example.warehouseManagement.Domains.ShipmentStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Form backing object for the status update on the shipment details page. */
@Data
@NoArgsConstructor
public class ShipmentStatusUpdateDto {
    @NotNull(message = "Status is required")
    private ShipmentStatus status;
    @Size(max = 255, message = "Location must be at most 255 characters")
    private String location;
    @Size(max = 500, message = "Note must be at most 500 characters")
    private String note;
    /** Optional; defaults to now. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime occurredAt;
}
