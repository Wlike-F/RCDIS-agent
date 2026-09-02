package com.rcdis.agent.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.rcdis.agent.common.aop.AuditReasonProvider;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ExpenseCreateRequest(
        @NotNull Long projectId,
        @NotNull Long budgetCategoryId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotNull LocalDate expenseDate,
        @Size(max = 128) String vendor,
        @Size(max = 128) String invoiceNo,
        @Size(max = 255) String receiptFile,
        @NotBlank @Size(max = 500) String description,
        @NotBlank @Size(max = 500) String reason
) implements AuditReasonProvider {

    @Override
    public String auditReason() {
        return reason;
    }
}
