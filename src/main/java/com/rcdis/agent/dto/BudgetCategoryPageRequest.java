package com.rcdis.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BudgetCategoryPageRequest(
        @Min(1) long current,
        @Min(1) @Max(500) long size,
        @Size(max = 128) String keyword,
        @Pattern(regexp = "ACTIVE|DISABLED") String status
) {
}
