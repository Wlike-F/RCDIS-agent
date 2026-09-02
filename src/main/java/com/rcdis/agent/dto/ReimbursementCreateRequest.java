package com.rcdis.agent.dto;

import java.util.List;

import com.rcdis.agent.common.aop.AuditReasonProvider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReimbursementCreateRequest(
        @NotNull Long projectId,
        @NotBlank @Size(max = 64) String applicant,
        // expenseIds and newExpenses must not both be empty; validated in the service layer.
        List<@NotNull Long> expenseIds,
        @Valid List<QuickExpenseInput> newExpenses,
        @Size(max = 500) String reason,
        // When true the order is submitted right after creation; it stays DRAFT if the material check fails.
        Boolean submitNow
) implements AuditReasonProvider {

    @Override
    public String auditReason() {
        return reason;
    }
}
