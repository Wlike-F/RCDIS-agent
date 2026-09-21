package com.rcdis.agent.dto;

import java.util.List;

import com.rcdis.agent.common.aop.AuditReasonProvider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Update a DRAFT reimbursement order: applicant, full replacement item list and reason.
 * The full item list is expected; lines are replaced on the server side.
 * version is required for optimistic locking.
 */
public record ReimbursementUpdateRequest(
        @NotBlank @Size(max = 64) String applicant,
        @Valid @NotEmpty List<ReimbursementItemInput> items,
        @Size(max = 500) String reason,
        @NotNull Integer version
) implements AuditReasonProvider {

    @Override
    public String auditReason() {
        return reason;
    }
}
