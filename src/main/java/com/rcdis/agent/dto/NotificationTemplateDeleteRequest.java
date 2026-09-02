package com.rcdis.agent.dto;

import com.rcdis.agent.common.aop.AuditReasonProvider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NotificationTemplateDeleteRequest(
        @NotBlank @Size(max = 500) String reason
) implements AuditReasonProvider {

    @Override
    public String auditReason() {
        return reason;
    }
}
