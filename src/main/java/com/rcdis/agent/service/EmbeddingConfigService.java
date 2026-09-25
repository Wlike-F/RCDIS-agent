package com.rcdis.agent.service;

import com.rcdis.agent.vo.EmbeddingConfigVO;

/**
 * Runtime configuration for the semantic-memory embedding model. Admin choices persist in
 * {@code app_setting} and take effect immediately; {@code application.yml} only holds the defaults.
 *
 * <p>This lets an operator point semantic recall at any OpenAI-compatible embeddings endpoint
 * (DashScope text-embedding-v3/v4, a SenseNova/piccolo provider, etc.) from the UI, exactly like the
 * receipt-OCR model selection.</p>
 */
public interface EmbeddingConfigService {

    /** Current embedding configuration plus the selectable provider/model options. */
    EmbeddingConfigVO current();

    /**
     * Updates the embedding configuration (null fields keep the current value), validates that the
     * provider exists, persists it and returns the new configuration.
     */
    EmbeddingConfigVO update(Boolean enabled, String providerId, String model, String embeddingsPath);

    /** Effective semantic-retrieval switch (app_setting override, else application.yml default). */
    boolean enabled();

    /** Configured embedding provider code, or blank to mean "use the default chat provider". */
    String providerId();

    /** Embedding model name to send to the provider. */
    String model();

    /** Embeddings path appended to the provider base URL. */
    String embeddingsPath();

    /** Fixed pgvector column dimension; embeddings of a different size are rejected. */
    int dimension();
}
