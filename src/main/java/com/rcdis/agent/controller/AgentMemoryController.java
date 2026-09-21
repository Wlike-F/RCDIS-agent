package com.rcdis.agent.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.dto.MemorySettingUpdateRequest;
import com.rcdis.agent.service.AgentSemanticMemoryService;
import com.rcdis.agent.vo.MemorySettingVO;
import com.rcdis.agent.vo.SemanticMemoryVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Cross-session semantic memory: per-user read/write switches and the memory list for the panel.
 */
@Tag(name = "Agent Memory", description = "Cross-session semantic memory switches and list")
@RestController
@RequestMapping("/api/agent/memory")
@RequiredArgsConstructor
public class AgentMemoryController {

    private final AgentSemanticMemoryService agentSemanticMemoryService;

    @Operation(summary = "Get current user's semantic-memory switches (merged with global)")
    @GetMapping("/setting")
    public ApiResponse<MemorySettingVO> setting() {
        return ApiResponse.success(agentSemanticMemoryService.getSetting());
    }

    @Operation(summary = "Update current user's extract / inject switches")
    @PutMapping("/setting")
    public ApiResponse<MemorySettingVO> updateSetting(
            @Valid @RequestBody MemorySettingUpdateRequest request) {
        agentSemanticMemoryService.updateSetting(request.extractEnabled(), request.injectEnabled());
        return ApiResponse.success(agentSemanticMemoryService.getSetting());
    }

    @Operation(summary = "List current user's cross-session semantic memories")
    @GetMapping("/list")
    public ApiResponse<List<SemanticMemoryVO>> list() {
        return ApiResponse.success(agentSemanticMemoryService.listForCurrentUser());
    }
}
