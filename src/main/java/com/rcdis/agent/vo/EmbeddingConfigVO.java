package com.rcdis.agent.vo;

import java.util.List;

/**
 * Admin-facing embedding configuration: the current selection plus the selectable providers.
 *
 * <p>Mirrors {@link OcrConfigVO}. {@code enabled} is the semantic-retrieval master switch; when off,
 * memory injection stays on the legacy newest-N path. {@code providerId} may be blank to mean
 * "use the default chat provider". {@code dimension} is the fixed pgvector column width and is
 * exposed read-only: it is set by the Flyway migration at startup and changing the model to one
 * with a different output size needs a re-index migration, not an edit here.</p>
 */
public record EmbeddingConfigVO(
        boolean enabled,
        String providerId,
        String model,
        String embeddingsPath,
        int dimension,
        List<ModelProviderVO> providers
) {
}
