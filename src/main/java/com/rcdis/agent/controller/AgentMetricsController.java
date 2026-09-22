package com.rcdis.agent.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.service.AgentMetricsQueryService;
import com.rcdis.agent.vo.AgentMetricsPeriodVO;
import com.rcdis.agent.vo.AgentMetricsSummaryVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Administrator-facing Agent business metrics. */
@Tag(name = "Agent Metrics")
@RestController
@RequestMapping("/api/admin/agent-metrics")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AgentMetricsController {

    private final AgentMetricsQueryService agentMetricsQueryService;

    @Operation(summary = "Get the current Agent business metrics snapshot")
    @GetMapping("/summary")
    public ApiResponse<AgentMetricsSummaryVO> summary() {
        return ApiResponse.success(agentMetricsQueryService.summary());
    }

    @Operation(summary = "Get restart-safe period metrics aggregated from agent_turn_trace")
    @GetMapping("/period")
    public ApiResponse<AgentMetricsPeriodVO> period(
            @RequestParam(name = "days", defaultValue = "7") int days) {
        return ApiResponse.success(agentMetricsQueryService.periodSummary(days));
    }
}
