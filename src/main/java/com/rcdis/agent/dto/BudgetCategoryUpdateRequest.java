package com.rcdis.agent.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BudgetCategoryUpdateRequest(
        @NotBlank @Size(max = 128) String categoryName,
        @NotNull @DecimalMin(value = "0.00") BigDecimal allocatedAmount,
        @NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status,
        @Size(max = 500) String remark,
        @NotNull Integer version
) {
}
