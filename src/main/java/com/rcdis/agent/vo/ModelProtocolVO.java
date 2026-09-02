package com.rcdis.agent.vo;

import java.util.List;

/**
 * Describes one wire protocol a custom model endpoint can implement.
 *
 * <p>Drives the provider form in the frontend and documents what an endpoint must expose.</p>
 */
public record ModelProtocolVO(
        String code,
        String label,
        String description,
        String chatCompletionsPath,
        String modelsPath,
        boolean requiresApiKey,
        boolean supported,
        List<String> compatibleVendors
) {
}
