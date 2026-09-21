package com.rcdis.agent.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        String conversationId,
        String providerId,
        @NotBlank String message,
        /** Optional uploaded attachment ids whose extracted text is injected into this turn. */
        List<Long> attachmentIds
) {
}

