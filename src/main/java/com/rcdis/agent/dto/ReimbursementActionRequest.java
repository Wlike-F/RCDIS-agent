package com.rcdis.agent.dto;

import com.rcdis.agent.common.aop.AuditReasonProvider;

import jakarta.validation.constraints.Size;

/**
 * Shared request body for reimbursement transitions (submit / approve / reject).
 * The service decides whether the reason is mandatory per transition.
 */
public record ReimbursementActionRequest(
        @Size(max = 500) String reason
) implements AuditReasonProvider {

    @Override
    public String auditReason() {
        return reason;
    }
}
