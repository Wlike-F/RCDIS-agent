package com.rcdis.agent.service.impl;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.entity.AgentMemoryEntity;
import com.rcdis.agent.infrastructure.ai.EmbeddingClient;
import com.rcdis.agent.mapper.AgentMemoryMapper;
import com.rcdis.agent.service.EmbeddingConfigService;
import com.rcdis.agent.service.ModelProviderService;
import com.rcdis.agent.to.ModelEndpointTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Single, cycle-free owner of embedding generation and storage for semantic memory.
 *
 * <p>Depends only on the embedding client, the provider resolver, config, and the mapper — never on
 * {@code AgentSemanticMemoryService} — so both the extraction write path and the retrieval read path
 * can depend on it without forming a bean cycle. Every method is best-effort: a missing feature flag,
 * absent pgvector column, or embedding-API failure degrades quietly (returns null / 0) rather than
 * throwing, so no caller's turn or write is ever broken by embeddings.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticMemoryEmbedder {

    private final EmbeddingClient embeddingClient;
    private final ModelProviderService modelProviderService;
    private final AgentProperties agentProperties;
    private final AgentMemoryMapper agentMemoryMapper;
    private final EmbeddingConfigService embeddingConfigService;

    /** True only when the semantic-retrieval feature is enabled (guards embedding spend). */
    public boolean isEnabled() {
        return embeddingConfigService.enabled();
    }

    /**
     * Embeds text and returns the pgvector text literal {@code [v1,v2,...]}, or null when embedding
     * is disabled, unavailable, or the model's output dimension does not match the column. The
     * dimension check turns a wrong-model choice into a clear WARN here instead of a silent vector
     * insert failure later.
     */
    public String embedLiteral(String text) {
        return embedLiteralInternal(text, true);
    }

    /**
     * Probe-only embedding: runs the same path as {@link #embedLiteral(String)} but ignores the
     * master switch, so an operator can preview vector recall and tune the hybrid weight from the
     * developer console BEFORE committing to enabling semantic retrieval. Still honours the
     * dimension guard. Never invoked by the live turn path.
     */
    public String embedLiteralForProbe(String text) {
        return embedLiteralInternal(text, false);
    }

    private String embedLiteralInternal(String text, boolean requireEnabled) {
        if ((requireEnabled && !isEnabled()) || !StringUtils.hasText(text)) {
            return null;
        }
        int expectedDimension = embeddingConfigService.dimension();
        try {
            String providerId = embeddingConfigService.providerId();
            // Blank provider -> reuse the default chat provider (and its key).
            ModelEndpointTO endpoint = modelProviderService.resolveEndpoint(
                    StringUtils.hasText(providerId) ? providerId : null, null);
            List<Double> vector = embeddingClient.embed(
                    endpoint, embeddingConfigService.embeddingsPath(), embeddingConfigService.model(), text);
            if (vector.size() != expectedDimension) {
                log.atWarn()
                        .addKeyValue("expectedDimension", expectedDimension)
                        .addKeyValue("actualDimension", vector.size())
                        .addKeyValue("embeddingModel", embeddingConfigService.model())
                        .log("Embedding dimension mismatch with pgvector column; skipping (align the model or migrate the column)");
                return null;
            }
            return toVectorLiteral(vector);
        } catch (RuntimeException exception) {
            log.atWarn()
                    .setCause(exception)
                    .log("Semantic memory embedding unavailable; callers will degrade");
            return null;
        }
    }

    /** Generates and stores the embedding for one freshly extracted fact. Best-effort. */
    public void embedAndStore(Long memoryId, String content) {
        if (memoryId == null) {
            return;
        }
        String literal = embedLiteral(content);
        if (literal == null) {
            return;
        }
        try {
            agentMemoryMapper.updateEmbedding(memoryId, literal);
        } catch (DataAccessException exception) {
            log.atWarn()
                    .setCause(exception)
                    .addKeyValue("memoryId", memoryId)
                    .log("Failed to persist semantic memory embedding; retrieval will degrade for this row");
        }
    }

    /**
     * Backfills embeddings for facts that lack one, bounded by {@code limit}.
     *
     * @return the number of rows successfully embedded
     */
    public int backfillMissingEmbeddings(int limit) {
        if (!isEnabled() || limit <= 0) {
            return 0;
        }
        // Explicit, bounded page so a large table is never swept in one call (AGENTS.md limit rule).
        int bounded = Math.min(limit, Math.max(1, agentProperties.getMemory().getSemanticMaxStored()) * 10);
        List<AgentMemoryEntity> pending;
        try {
            pending = agentMemoryMapper.selectMissingEmbedding(bounded);
        } catch (DataAccessException exception) {
            // The embedding column is absent (pgvector not installed when V4 ran); fail-safe.
            log.atWarn()
                    .setCause(exception)
                    .log("Embedding backfill skipped: agent_memory.embedding column unavailable");
            return 0;
        }
        int done = 0;
        for (AgentMemoryEntity row : pending) {
            String literal = embedLiteral(row.getContent());
            if (literal == null) {
                continue;
            }
            try {
                if (agentMemoryMapper.updateEmbedding(row.getId(), literal) > 0) {
                    done++;
                }
            } catch (DataAccessException exception) {
                log.atWarn()
                        .setCause(exception)
                        .addKeyValue("memoryId", row.getId())
                        .log("Embedding backfill write failed; continuing");
            }
        }
        log.atInfo().addKeyValue("embedded", done).addKeyValue("scanned", bounded)
                .log("Semantic memory embedding backfill pass completed");
        return done;
    }

    private static String toVectorLiteral(List<Double> vector) {
        StringBuilder sb = new StringBuilder(vector.size() * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector.get(i));
        }
        sb.append(']');
        return sb.toString();
    }
}
