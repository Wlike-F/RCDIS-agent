package com.rcdis.agent.dto;

import com.rcdis.agent.common.aop.AuditReasonProvider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record NotificationTemplateUpdateRequest(
        @NotBlank @Size(max = 128) String templateName,
        @Size(max = 64) String scene,
        @Size(max = 500) String description,
        @NotBlank @Pattern(regexp = "text|interactive") String messageType,
        @NotBlank String content,
        @Pattern(regexp = "ACTIVE|DISABLED") String status,
        @NotNull Integer version,
        @Size(max = 500) String reason
) implements AuditReasonProvider {

    @Override
    public String auditReason() {
        return reason;
    }
}
