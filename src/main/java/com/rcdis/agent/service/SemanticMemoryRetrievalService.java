package com.rcdis.agent.service;

import java.util.List;

import com.rcdis.agent.entity.AgentMemoryEntity;

/**
 * Read path for cross-session semantic memory, with optional pgvector hybrid relevance retrieval.
 *
 * <p>Owns the injection query end to end: it applies the effective-inject gate, runs the relevance
 * lanes when {@code semantic-retrieval} is enabled, and degrades through keyword-only to the legacy
 * newest-N injection whenever the vector prerequisites (pgvector column, embedding model, live API)
 * are unavailable — so the conversation turn never fails because of retrieval.</p>
 */
public interface SemanticMemoryRetrievalService {

    /**
     * Memories to inject for this user given the current query. Respects the inject switch; returns
     * an empty list when injection is off. Runs pgvector hybrid relevance when enabled and degrades
     * to keyword-only then the legacy newest-N set whenever vector prerequisites are unavailable.
     */
    List<AgentMemoryEntity> retrieve(String userId, String queryText);
}
