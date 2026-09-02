package com.rcdis.agent.dto;

import java.util.List;

import com.rcdis.agent.common.aop.AuditReasonProvider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Update a DRAFT reimbursement order: applicant, linked expense ids and reason.
 * The full replacement expense list is expected; items are diffed on the server side.
 * version is required for optimistic locking.
 */
public record ReimbursementUpdateRequest(
        @NotBlank @Size(max = 64) String applicant,
        @NotNull List<@NotNull Long> expenseIds,
        @Size(max = 500) String reason,
        @NotNull Integer version
) implements AuditReasonProvider {

    @Override
    public String auditReason() {
        return reason;
    }
}
