package com.rcdis.agent.vo;

import java.time.OffsetDateTime;

/**
 * Persisted outcome of the most recent connectivity probe for a provider.
 */
public record ModelProviderTestResultVO(
        String status,
        String message,
        Integer httpStatus,
        Long latencyMs,
        OffsetDateTime testedAt
) {
}
