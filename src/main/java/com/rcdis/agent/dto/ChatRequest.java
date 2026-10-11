package com.rcdis.agent.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        String conversationId,
        String providerId,
        @NotBlank String message,
        /** Optional uploaded attachment ids whose extracted text is injected into this turn. */
        List<Long> attachmentIds,
        /**
         * Optional field values the user filled in through an {@code agent_needs_input} card,
         * keyed by the field name the card listed (e.g. {@code projectCode}, {@code reimbursementNo}).
         * Injected into the volatile context block as "user-provided this turn" facts so the model
         * uses them instead of re-prompting.
         */
        Map<String, String> fieldValues
) {
}
