package com.rcdis.agent.dto;

import com.rcdis.agent.common.aop.AuditReasonProvider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Soft-deletes a login account. The reason is mandatory because removing access is an audited,
 * security-relevant action.
 */
public record UserDeleteRequest(
        @NotBlank @Size(max = 500) String reason
) implements AuditReasonProvider {

    @Override
    public String auditReason() {
        return reason;
    }
}
