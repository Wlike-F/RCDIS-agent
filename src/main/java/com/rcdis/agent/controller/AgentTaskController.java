package com.rcdis.agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.service.AgentTaskService;
import com.rcdis.agent.vo.AgentTaskVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Read-only task inspection; execution remains exclusively behind pending-action confirmation. */
@Tag(name = "Agent Tasks")
@RestController
@RequestMapping("/api/agent-tasks")
@RequiredArgsConstructor
public class AgentTaskController {

    private final AgentTaskService agentTaskService;

    @Operation(summary = "Get one owned Plan-and-Execute task with ordered steps")
    @GetMapping("/{id}")
    public ApiResponse<AgentTaskVO> get(@PathVariable Long id) {
        return ApiResponse.success(agentTaskService.get(id));
    }
}
