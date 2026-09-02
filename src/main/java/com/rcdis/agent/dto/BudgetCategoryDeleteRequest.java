package com.rcdis.agent.dto;

import com.rcdis.agent.common.aop.AuditReasonProvider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BudgetCategoryDeleteRequest(
        @NotBlank @Size(max = 500) String reason,
        @NotNull Integer version
) implements AuditReasonProvider {

    @Override
    public String auditReason() {
        return reason;
    }
}
