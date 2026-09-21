package com.rcdis.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * User decision on a pending high-risk Agent action surfaced by the confirmation card.
 */
public record ChatConfirmRequest(
        @NotBlank String conversationId,
        @NotBlank String confirmationId,
        @NotNull Boolean approved
) {
}
