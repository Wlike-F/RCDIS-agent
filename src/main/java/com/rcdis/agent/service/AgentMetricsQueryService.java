package com.rcdis.agent.service;

import com.rcdis.agent.vo.AgentMetricsSummaryVO;

/** Provides a frontend-oriented snapshot of the Agent's Micrometer metrics. */
public interface AgentMetricsQueryService {

    AgentMetricsSummaryVO summary();
}
