package com.example.warehouseManagement.Domains.DTOs;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class PickingJobLineDto {
    /** Id of the picking job line this entry belongs to (optional; falls back to position). */
    private Long pickingJobLineId;
    @Builder.Default
    private Long wareHouseSectionId = 0L;
    @Builder.Default
    @jakarta.validation.constraints.Min(0)
    private int qtyPicked = 0;
}
