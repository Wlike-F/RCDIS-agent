package com.rcdis.agent.dto;

import java.util.List;

import com.rcdis.agent.common.aop.AuditReasonProvider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReimbursementCreateRequest(
        @NotNull Long projectId,
        @NotBlank @Size(max = 64) String applicant,
        // reimbursement | public_payment; validated in the service layer.
        @NotBlank @Size(max = 32) String paymentType,
        @Valid @NotEmpty List<ReimbursementItemInput> items,
        @Size(max = 500) String reason,
        // When true the order is submitted right after creation: a public payment books
        // immediately, a reimbursement stays DRAFT if the material check fails.
        Boolean submitNow
) implements AuditReasonProvider {

    @Override
    public String auditReason() {
        return reason;
    }
}
