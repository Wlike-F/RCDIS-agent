package com.rcdis.agent.vo;

import java.util.List;

/**
 * Result of pulling the model catalogue from a provider endpoint.
 */
public record ModelProviderDiscoveryVO(
        String providerId,
        List<String> discoveredModels,
        int persistedCount,
        int skippedCount,
        String message
) {
}
