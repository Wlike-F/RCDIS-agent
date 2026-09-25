package com.rcdis.agent.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.rcdis.agent.agent.AgentMetrics;
import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.entity.AgentMemoryEntity;
import com.rcdis.agent.mapper.AgentMemoryMapper;
import com.rcdis.agent.service.AgentSemanticMemoryService;
import com.rcdis.agent.service.SemanticMemoryRetrievalService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link SemanticMemoryRetrievalService}.
 *
 * <p>Everything here fails safe. The whole relevance path sits behind
 * {@code rcdis.agent.memory.semantic-retrieval-enabled}; when off, injection is the unchanged
 * newest-N behaviour. When on, a missing embedding, an absent pgvector column, or an unavailable
 * {@code pg_trgm} extension each degrade independently, so the turn always receives some (possibly
 * newest-N) memory set and is never broken by retrieval. Embedding generation is delegated to
 * {@link SemanticMemoryEmbedder} to keep the dependency graph acyclic.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticMemoryRetrievalServiceImpl implements SemanticMemoryRetrievalService {

    private final AgentSemanticMemoryService agentSemanticMemoryService;
    private final AgentMemoryMapper agentMemoryMapper;
    private final SemanticMemoryEmbedder embedder;
    private final AgentProperties agentProperties;
    private final AgentMetrics agentMetrics;

    @Override
    public List<AgentMemoryEntity> retrieve(String userId, String queryText) {
        if (userId == null || !agentSemanticMemoryService.effectiveInject(userId)) {
            return List.of();
        }
        AgentProperties.Memory mem = agentProperties.getMemory();
        if (!embedder.isEnabled() || !StringUtils.hasText(queryText)) {
            return agentSemanticMemoryService.loadForInjection(userId);
        }

        int candidateLimit = Math.max(1, mem.getSemanticCandidateLimit());
        int topK = Math.max(1, mem.getSemanticMaxInject());

        List<AgentMemoryEntity> vectorLane = List.of();
        String queryVectorLiteral = embedder.embedLiteral(queryText);
        if (queryVectorLiteral != null) {
            vectorLane = laneSafely("vector",
                    () -> agentMemoryMapper.searchByVector(userId, queryVectorLiteral, candidateLimit));
        }
        List<AgentMemoryEntity> keywordLane = laneSafely("keyword",
                () -> agentMemoryMapper.searchByKeyword(userId, queryText, candidateLimit));

        if (vectorLane.isEmpty() && keywordLane.isEmpty()) {
            // No relevance lane produced anything (extension absent, nothing embedded yet, or no
            // match). Fall back to the legacy newest-N set so behaviour never regresses to empty.
            agentMetrics.recordMemoryRetrieval("newest_fallback");
            return agentSemanticMemoryService.loadForInjection(userId);
        }

        List<Long> fusedIds = RrfFuser.fuse(ids(vectorLane), ids(keywordLane), mem.getSemanticHybridAlpha(), topK);
        List<AgentMemoryEntity> fused = reorderByIds(fusedIds, vectorLane, keywordLane);
        agentMetrics.recordMemoryRetrieval(laneMode(vectorLane.isEmpty(), keywordLane.isEmpty()));
        return fused;
    }

    // ---------- helpers ----------

    private List<AgentMemoryEntity> laneSafely(String lane, LaneQuery query) {
        try {
            List<AgentMemoryEntity> rows = query.run();
            return rows == null ? List.of() : rows;
        } catch (DataAccessException exception) {
            // pgvector / pg_trgm not installed, or column absent — this lane is simply off.
            log.atDebug()
                    .setCause(exception)
                    .addKeyValue("lane", lane)
                    .log("Semantic memory retrieval lane unavailable; degrading");
            return List.of();
        }
    }

    private static List<Long> ids(List<AgentMemoryEntity> rows) {
        List<Long> ids = new ArrayList<>(rows.size());
        for (AgentMemoryEntity row : rows) {
            if (row.getId() != null) {
                ids.add(row.getId());
            }
        }
        return ids;
    }

    @SafeVarargs
    private static List<AgentMemoryEntity> reorderByIds(List<Long> orderedIds, List<AgentMemoryEntity>... lanes) {
        Map<Long, AgentMemoryEntity> byId = new LinkedHashMap<>();
        for (List<AgentMemoryEntity> lane : lanes) {
            for (AgentMemoryEntity row : lane) {
                if (row.getId() != null) {
                    byId.putIfAbsent(row.getId(), row);
                }
            }
        }
        List<AgentMemoryEntity> result = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) {
            AgentMemoryEntity row = byId.get(id);
            if (row != null) {
                result.add(row);
            }
        }
        return result;
    }

    private static String laneMode(boolean vectorEmpty, boolean keywordEmpty) {
        if (!vectorEmpty && !keywordEmpty) {
            return "hybrid";
        }
        return vectorEmpty ? "keyword_only" : "vector_only";
    }

    /** A lane query that can throw {@link DataAccessException} when its extension is missing. */
    @FunctionalInterface
    private interface LaneQuery {
        List<AgentMemoryEntity> run();
    }
}
