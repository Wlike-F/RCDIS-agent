package com.rcdis.agent.vo;

import java.util.List;

/**
 * Period-aggregated Agent metrics computed from the persisted {@code agent_turn_trace} table,
 * so they survive backend restarts and support a selectable window (default 7 days, max 15).
 */
public record AgentMetricsPeriodVO(
        int days,
        String windowStart,
        String windowEnd,
        long turns,
        long doneTurns,
        long errorTurns,
        long timeoutTurns,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        Double avgFirstTokenMs,
        Double avgTotalMs,
        long toolCallsTotal,
        long toolCallsSuccess,
        Double toolSuccessRate,
        boolean costConfigured,
        double estimatedCost,
        List<ProviderStatVO> providers,
        String generatedAt
) {

    /** Per provider+model roll-up within the window. */
    public record ProviderStatVO(
            String providerCode,
            String modelName,
            long turns,
            long totalTokens
    ) {
    }
}
