package com.rcdis.agent.service;

import com.rcdis.agent.vo.AgentMetricsPeriodVO;
import com.rcdis.agent.vo.AgentMetricsSummaryVO;

/** Provides a frontend-oriented snapshot of the Agent's Micrometer metrics. */
public interface AgentMetricsQueryService {

    /** Current in-process snapshot (meters reset on restart). */
    AgentMetricsSummaryVO summary();

    /**
     * Restart-safe period metrics aggregated from the persisted {@code agent_turn_trace} rows
     * within the given window; days is clamped to [1, configured max].
     */
    AgentMetricsPeriodVO periodSummary(int days);
}
