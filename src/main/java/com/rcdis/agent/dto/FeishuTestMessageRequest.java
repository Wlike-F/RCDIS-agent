package com.rcdis.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FeishuTestMessageRequest(
        String target,
        String receiveIdType,
        @NotBlank @Size(max = 500) String text,
        String idempotencyKey
) {
}
