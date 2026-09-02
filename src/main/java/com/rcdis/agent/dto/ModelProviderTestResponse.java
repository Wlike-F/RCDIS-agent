package com.rcdis.agent.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Outcome of a real connectivity probe.
 *
 * <p>{@code status} uses the values defined on
 * {@link com.rcdis.agent.to.ProviderProbeTO}, plus {@code NOT_READY} when no endpoint call was
 * attempted. The message is sanitized and never contains API key material.</p>
 */
public record ModelProviderTestResponse(
        String providerId,
        boolean configured,
        boolean enabled,
        String status,
        String message,
        boolean success,
        Integer httpStatus,
        Long latencyMs,
        String testedModel,
        List<String> discoveredModels,
        OffsetDateTime testedAt
) {
}
