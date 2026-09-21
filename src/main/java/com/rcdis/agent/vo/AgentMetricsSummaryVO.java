package com.rcdis.agent.vo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Typed snapshot of the Agent business metrics currently held by Micrometer. */
public record AgentMetricsSummaryVO(
        OffsetDateTime generatedAt,
        ToolMetricsVO tools,
        TokenMetricsVO tokens,
        MemoryMetricsVO memory,
        TaggedCountVO turns,
        TaggedCountVO confirmations,
        TaggedCountVO taskSteps,
        TaggedCountVO recoveries,
        TaggedCountVO securityBlocks,
        LatencyMetricsVO firstTokenLatency,
        LatencyMetricsVO turnLatency) {

    public record ToolMetricsVO(
            long totalCalls,
            long successfulCalls,
            long failedCalls,
            double successRate,
            double averageDurationMs,
            List<ToolBreakdownVO> byTool) {

        public ToolMetricsVO {
            byTool = List.copyOf(byTool);
        }
    }

    public record ToolBreakdownVO(
            String tool,
            long totalCalls,
            long successfulCalls,
            long failedCalls,
            double successRate,
            double averageDurationMs) {
    }

    public record TokenMetricsVO(
            long promptTokens,
            long completionTokens,
            long totalTokens,
            double estimatedCost,
            boolean costConfigured) {
    }

    public record MemoryMetricsVO(
            long hits,
            long misses,
            long totalInjections,
            long itemsInjected,
            double hitRate) {
    }

    public record TaggedCountVO(long total, Map<String, Long> values) {

        public TaggedCountVO {
            values = Map.copyOf(values);
        }
    }

    public record LatencyMetricsVO(long sampleCount, double averageMs, double maxMs) {
    }
}
