package com.rcdis.agent.dto;

/**
 * Outcome of resolving a pending Agent action.
 */
public record ChatConfirmResponse(
        String confirmationId,
        String status,
        boolean executed,
        String message
) {
}
