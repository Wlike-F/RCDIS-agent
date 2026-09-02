package com.rcdis.agent.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        String conversationId,
        String providerId,
        @NotBlank String message
) {
}

