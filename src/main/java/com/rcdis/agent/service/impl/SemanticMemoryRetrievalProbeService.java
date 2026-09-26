package com.rcdis.agent.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.mapper.AgentMemoryMapper;
import com.rcdis.agent.service.EmbeddingConfigService;
import com.rcdis.agent.to.MemoryRecallTO;
import com.rcdis.agent.vo.MemoryRetrievalProbeVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-only diagnostic that runs the semantic-memory hybrid retrieval for an arbitrary query and
 * surfaces the per-lane scores (cosine distance, trigram similarity, fused rank) so an operator can
 * judge embedding quality and tune the fusion weight.
 *
 * <p>It bypasses the master switch (via {@link SemanticMemoryEmbedder#embedLiteralForProbe}) so recall
 * can be previewed before enabling the feature, but it never writes and never touches the live turn
 * path. Each lane degrades independently: a missing pgvector/pg_trgm extension or an embedding failure
 * is reported in the response rather than thrown.</p>
 *
 * <p>The vector lane is queried <b>without</b> the live cosine-distance gate on purpose: the whole
 * point of the probe is to let an operator see the real distance distribution and pick a threshold.
 * Each hit is instead marked with {@code withinThreshold}, evaluated by the same
 * {@link VectorDistanceGate} the live path uses, so the effect of the currently configured threshold
 * is visible without changing it.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticMemoryRetrievalProbeService {

    private final EmbeddingConfigService embeddingConfigService;
    private final SemanticMemoryEmbedder embedder;
    private final AgentMemoryMapper agentMemoryMapper;
    private final AgentProperties agentProperties;

    public MemoryRetrievalProbeVO probe(String userId, String query, Integer topKParam) {
        AgentProperties.Memory mem = agentProperties.getMemory();
        int topK = clampTopK(topKParam, mem);
        int candidates = Math.max(topK, Math.max(1, mem.getSemanticCandidateLimit()));
        double alpha = mem.getSemanticHybridAlpha();
        double maxDistance = VectorDistanceGate.effectiveMaxDistance(mem.getSemanticVectorMaxDistance());
        boolean enabled = embeddingConfigService.enabled();
        String providerLabel = StringUtils.hasText(embeddingConfigService.providerId())
                ? embeddingConfigService.providerId()
                : "default";
        String model = embeddingConfigService.model();
        int dimension = embeddingConfigService.dimension();

        if (!StringUtils.hasText(userId) || !StringUtils.hasText(query)) {
            return new MemoryRetrievalProbeVO(query, userId, enabled, "no_match", false, false,
                    providerLabel, model, dimension, alpha, maxDistance, topK, List.of(),
                    "userId 与 query 均不能为空。");
        }

        List<MemoryRecallTO> vectorLane = List.of();
        boolean vectorAvailable = false;
        String queryVectorLiteral = embedder.embedLiteralForProbe(query);
        if (queryVectorLiteral != null) {
            try {
                vectorLane = nullSafe(agentMemoryMapper.searchByVectorScored(userId, queryVectorLiteral, candidates));
                vectorAvailable = true;
            } catch (DataAccessException exception) {
                log.atDebug().setCause(exception).log("Probe vector lane unavailable");
            }
        }

        List<MemoryRecallTO> keywordLane = List.of();
        boolean keywordAvailable = false;
        try {
            keywordLane = nullSafe(agentMemoryMapper.searchByKeywordScored(userId, query, candidates));
            keywordAvailable = true;
        } catch (DataAccessException exception) {
            log.atDebug().setCause(exception).log("Probe keyword lane unavailable");
        }

        // Merge both lanes by id, keeping each lane's score on the shared row.
        LinkedHashMap<Long, MemoryRecallTO> byId = new LinkedHashMap<>();
        for (MemoryRecallTO row : vectorLane) {
            if (row.getId() != null) {
                byId.put(row.getId(), row);
            }
        }
        for (MemoryRecallTO row : keywordLane) {
            if (row.getId() == null) {
                continue;
            }
            MemoryRecallTO existing = byId.get(row.getId());
            if (existing != null) {
                existing.setKeywordScore(row.getKeywordScore());
            } else {
                byId.put(row.getId(), row);
            }
        }

        List<Long> fusedIds = RrfFuser.fuse(ids(vectorLane), ids(keywordLane), alpha, topK);
        List<MemoryRecallTO> hits = new ArrayList<>(fusedIds.size());
        int rank = 1;
        for (Long id : fusedIds) {
            MemoryRecallTO row = byId.get(id);
            if (row != null) {
                row.setFusedRank(rank++);
                markThreshold(row, maxDistance);
                hits.add(row);
            }
        }

        String mode = laneMode(vectorLane, keywordLane);
        return new MemoryRetrievalProbeVO(query, userId, enabled, mode, vectorAvailable, keywordAvailable,
                providerLabel, model, dimension, alpha, maxDistance, topK, hits,
                buildNote(enabled, queryVectorLiteral, vectorAvailable, keywordAvailable, hits, maxDistance));
    }

    /**
     * Marks a hit with the live gate's verdict. Rows the vector lane never ranked (keyword-only hits,
     * {@code cosineDistance == null}) are left unmarked rather than marked "accepted", so the console
     * can tell "passed the gate" apart from "the gate did not apply".
     */
    private static void markThreshold(MemoryRecallTO row, double maxDistance) {
        if (row.getCosineDistance() != null) {
            row.setWithinThreshold(VectorDistanceGate.withinThreshold(row.getCosineDistance(), maxDistance));
        }
    }

    private static String laneMode(List<MemoryRecallTO> vector, List<MemoryRecallTO> keyword) {
        if (vector.isEmpty() && keyword.isEmpty()) {
            return "no_match";
        }
        if (!vector.isEmpty() && !keyword.isEmpty()) {
            return "hybrid";
        }
        return vector.isEmpty() ? "keyword_only" : "vector_only";
    }

    private static String buildNote(boolean enabled, String queryVectorLiteral,
                                    boolean vectorAvailable, boolean keywordAvailable,
                                    List<MemoryRecallTO> hits, double maxDistance) {
        List<String> notes = new ArrayList<>();
        if (!enabled) {
            notes.add("总开关未开启，线上注入仍走「最新 N 条」；此处为强制预览结果。");
        }
        if (queryVectorLiteral == null) {
            notes.add("向量通道不可用：embedding 生成失败（维度不符 / provider·key·路径有误）或功能关闭。");
        } else if (!vectorAvailable) {
            notes.add("向量通道 SQL 失败：pgvector 可能未安装或 agent_memory.embedding 列缺失。");
        }
        if (!keywordAvailable) {
            notes.add("关键词通道 SQL 失败：pg_trgm 可能未安装。");
        }
        if (hits.isEmpty()) {
            notes.add("未召回到任何记忆：该用户可能还没有已向量化记忆，请先执行「回填缺失的 embedding」。");
        }
        long gatedOut = hits.stream()
                .filter(hit -> Boolean.FALSE.equals(hit.getWithinThreshold()))
                .count();
        if (gatedOut > 0) {
            notes.add(String.format(
                    "当前阈值 maxDistance=%.3f 会滤除 %d/%d 条命中（withinThreshold=false）；"
                            + "若这些都是应该召回的，请适当调大阈值。",
                    maxDistance, gatedOut, hits.size()));
        } else if (queryVectorLiteral != null && vectorAvailable && !hits.isEmpty()) {
            notes.add(String.format(
                    "当前阈值 maxDistance=%.3f 下所有命中均通过（maxDistance=2.000 表示阈值已关闭）。",
                    maxDistance));
        }
        return notes.isEmpty() ? null : String.join(" ", notes);
    }

    private static List<Long> ids(List<MemoryRecallTO> rows) {
        List<Long> ids = new ArrayList<>(rows.size());
        for (MemoryRecallTO row : rows) {
            if (row.getId() != null) {
                ids.add(row.getId());
            }
        }
        return ids;
    }

    private static List<MemoryRecallTO> nullSafe(List<MemoryRecallTO> rows) {
        return rows == null ? List.of() : rows;
    }

    private static int clampTopK(Integer requested, AgentProperties.Memory mem) {
        int max = Math.max(1, mem.getSemanticMaxInject());
        if (requested == null || requested <= 0) {
            return max;
        }
        return Math.min(requested, max);
    }
}
