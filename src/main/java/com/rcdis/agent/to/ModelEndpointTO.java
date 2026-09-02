package com.rcdis.agent.to;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Resolved connection details for a single model provider endpoint.
 *
 * <p>Carries the plaintext API key so infrastructure clients can authenticate. The key is excluded
 * from JSON serialization and masked in {@link #toString()} so it can never leak into application
 * logs or audit snapshots.</p>
 */
public record ModelEndpointTO(
        String providerId,
        String providerName,
        String protocol,
        String baseUrl,
        String chatCompletionsPath,
        String modelsPath,
        @JsonIgnore String apiKey,
        int timeoutSeconds,
        String modelName,
        BigDecimal temperature,
        Integer maxTokens
) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "ModelEndpointTO[providerId=" + providerId
                + ", protocol=" + protocol
                + ", baseUrl=" + baseUrl
                + ", modelName=" + modelName
                + ", apiKeyConfigured=" + hasApiKey()
                + ", timeoutSeconds=" + timeoutSeconds + "]";
    }
}
