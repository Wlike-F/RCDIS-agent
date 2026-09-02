package com.rcdis.agent.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ExpensePageRequest(
        @Min(1) long current,
        @Min(1) @Max(500) long size,
        Long projectId,
        Long budgetCategoryId,
        @Pattern(regexp = "REGISTERED|REIMBURSED|VOIDED") String status,
        LocalDate startDate,
        LocalDate endDate,
        @Size(max = 128) String keyword
) {
}
