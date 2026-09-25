package com.rcdis.agent.vo;

import java.util.List;

import com.rcdis.agent.to.MemoryRecallTO;

/**
 * Result of a semantic-memory retrieval probe (admin diagnostic).
 *
 * <p>Exposes, for one query, which memories each lane recalled and how the fused ranking turned out,
 * so an operator can judge embedding quality (vector lane) against keyword match and tune the hybrid
 * weight before/after enabling the feature. It never mutates state.</p>
 *
 * @param query             the probe query text
 * @param userId            the memory owner whose store was searched
 * @param retrievalEnabled  whether the semantic-retrieval master switch is currently on
 * @param mode              which strategy actually produced the ranking: {@code hybrid},
 *                          {@code vector_only}, {@code keyword_only}, or {@code no_match}
 * @param vectorAvailable   false when the vector lane errored (e.g. pgvector/embedding missing)
 * @param keywordAvailable  false when the keyword lane errored (e.g. pg_trgm missing)
 * @param embeddingProvider provider code used for embeddings, or {@code default}
 * @param embeddingModel    embedding model name in effect
 * @param dimension         configured pgvector column dimension
 * @param alpha             vector-lane weight used for fusion
 * @param topK              how many fused hits are returned
 * @param hits              fused, rank-ordered recall rows with per-lane scores
 * @param note              human-readable caveat (e.g. switch off, dimension mismatch)
 */
public record MemoryRetrievalProbeVO(
        String query,
        String userId,
        boolean retrievalEnabled,
        String mode,
        boolean vectorAvailable,
        boolean keywordAvailable,
        String embeddingProvider,
        String embeddingModel,
        int dimension,
        double alpha,
        int topK,
        List<MemoryRecallTO> hits,
        String note
) {
}
