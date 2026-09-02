package com.rcdis.agent.dto;

import jakarta.validation.constraints.Size;

/**
 * Connectivity probe request.
 *
 * <p>{@code probeChat} additionally sends a minimal chat completion, which verifies the model name
 * end to end but consumes a small amount of quota.</p>
 */
public record ModelProviderTestRequest(
        String providerId,
        @Size(max = 128) String modelName,
        Boolean probeChat
) {

    public boolean shouldProbeChat() {
        return Boolean.TRUE.equals(probeChat);
    }
}
