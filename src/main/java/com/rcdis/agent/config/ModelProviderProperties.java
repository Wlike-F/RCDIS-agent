package com.rcdis.agent.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Bootstrap configuration for model providers.
 *
 * <p>Providers declared here are migrated into the {@code model_provider} table on first startup
 * and are afterwards managed through the API. This class stays the seed source and the place where
 * the master passphrase for stored API keys is read from.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rcdis.ai")
public class ModelProviderProperties {

    /**
     * Master passphrase used to derive the AES-GCM key that protects API keys stored in PostgreSQL.
     * Supplied through the {@code RCDIS_PROVIDER_SECRET_KEY} environment variable.
     */
    private String secretKey;

    private String defaultProvider;
    private Map<String, ProviderProperties> providers = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class ProviderProperties {

        private String name;
        /** Wire protocol. Currently only {@code openai-compatible} is implemented. */
        private String type;
        private String baseUrl;
        private String apiKey;
        private String chatModel;
        /** Chat completions path appended to {@code baseUrl}. Defaults to {@code /v1/chat/completions}. */
        private String chatCompletionsPath;
        /** Model listing path appended to {@code baseUrl}. Defaults to {@code /v1/models}. */
        private String modelsPath;
        private Integer timeoutSeconds;
        private String description;
        private boolean enabled;
    }
}
