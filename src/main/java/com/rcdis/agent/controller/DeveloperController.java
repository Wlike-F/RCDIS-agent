package com.rcdis.agent.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.service.AgentToolCatalogService;
import com.rcdis.agent.vo.AgentToolVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

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

    @Operation(summary = "List registered Agent tools with parameters and read/write category")
    @GetMapping("/agent-tools")
    public ApiResponse<List<AgentToolVO>> agentTools() {
        return ApiResponse.success(agentToolCatalogService.listTools());
    }
}
