package com.rcdis.agent.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.service.AgentToolCatalogService;
import com.rcdis.agent.service.impl.SemanticMemoryEmbedder;
import com.rcdis.agent.service.impl.SemanticMemoryRetrievalProbeService;
import com.rcdis.agent.vo.AgentToolVO;
import com.rcdis.agent.vo.MemoryRetrievalProbeVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

/**
 * Developer console endpoints (ADMIN only, enforced at the URL layer in SecurityConfiguration).
 *
 * <p>Intentionally a thin, extensible surface: the first capability is the Agent tool catalogue;
 * further developer diagnostics (prompt preview, memory inspection, etc.) hang off the same
 * {@code /api/developer} prefix later.</p>
 */
@Tag(name = "Developer")
@RestController
@RequestMapping("/api/developer")
@RequiredArgsConstructor
public class DeveloperController {

    private final AgentToolCatalogService agentToolCatalogService;
    private final SemanticMemoryEmbedder semanticMemoryEmbedder;
    private final SemanticMemoryRetrievalProbeService semanticMemoryRetrievalProbeService;

    @Operation(summary = "List registered Agent tools with parameters and read/write category")
    @GetMapping("/agent-tools")
    public ApiResponse<List<AgentToolVO>> agentTools() {
        return ApiResponse.success(agentToolCatalogService.listTools());
    }

    @Operation(summary = "Backfill pgvector embeddings for semantic memories that lack one (no-op when disabled)")
    @PostMapping("/memory/backfill-embeddings")
    public ApiResponse<Map<String, Integer>> backfillEmbeddings(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        int embedded = semanticMemoryEmbedder.backfillMissingEmbeddings(limit);
        return ApiResponse.success(Map.of("embedded", embedded));
    }

    @Operation(summary = "Probe semantic-memory hybrid retrieval for a query (read-only diagnostic; "
            + "shows per-lane scores and fused ranking)")
    @GetMapping("/memory/retrieval-probe")
    public ApiResponse<MemoryRetrievalProbeVO> retrievalProbe(
            @RequestParam(name = "userId", required = false) String userId,
            @RequestParam(name = "query") String query,
            @RequestParam(name = "topK", required = false) Integer topK) {
        String owner = StringUtils.hasText(userId)
                ? userId.trim()
                : CurrentUserContextHolder.currentOrAnonymous().userId();
        return ApiResponse.success(semanticMemoryRetrievalProbeService.probe(owner, query, topK));
    }
}
