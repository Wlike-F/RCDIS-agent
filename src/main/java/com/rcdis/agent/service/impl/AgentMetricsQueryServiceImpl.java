package com.rcdis.agent.service.impl;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.entity.AgentTurnTraceEntity;
import com.rcdis.agent.mapper.AgentTurnTraceMapper;
import com.rcdis.agent.service.AgentMetricsQueryService;
import com.rcdis.agent.vo.AgentMetricsPeriodVO;
import com.rcdis.agent.vo.AgentMetricsSummaryVO;
import com.rcdis.agent.vo.AgentMetricsSummaryVO.LatencyMetricsVO;
import com.rcdis.agent.vo.AgentMetricsSummaryVO.MemoryMetricsVO;
import com.rcdis.agent.vo.AgentMetricsSummaryVO.TaggedCountVO;
import com.rcdis.agent.vo.AgentMetricsSummaryVO.TokenMetricsVO;
import com.rcdis.agent.vo.AgentMetricsSummaryVO.ToolBreakdownVO;
import com.rcdis.agent.vo.AgentMetricsSummaryVO.ToolMetricsVO;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;

/** Aggregates low-level meters into a stable API contract for the observability page. */
@Service
@RequiredArgsConstructor
public class AgentMetricsQueryServiceImpl implements AgentMetricsQueryService {

    private static final String TOOL_CALLS = "rcdis_agent_tool_calls_total";
    private static final String TOOL_DURATION = "rcdis_agent_tool_duration";
    private static final String TURNS = "rcdis_agent_turns_total";
    private static final String TOKENS = "rcdis_agent_tokens_total";
    private static final String TOKEN_COST = "rcdis_agent_token_cost_estimate_total";
    private static final String MEMORY_INJECTIONS = "rcdis_agent_memory_injection_total";
    private static final String MEMORY_ITEMS = "rcdis_agent_memory_items_total";
    private static final String CONFIRMATIONS = "rcdis_agent_confirmations_total";
    private static final String TASK_STEPS = "rcdis_agent_task_steps_total";
    private static final String RECOVERIES = "rcdis_agent_recoveries_total";
    private static final String SECURITY_BLOCKS = "rcdis_agent_security_blocks_total";
    private static final String FIRST_TOKEN_DURATION = "rcdis_agent_first_token_duration";
    private static final String TURN_DURATION = "rcdis_agent_turn_duration";

    private final MeterRegistry meterRegistry;
    private final AgentProperties agentProperties;
    private final AgentTurnTraceMapper agentTurnTraceMapper;
    private final ObjectMapper objectMapper;

    @Override
    public AgentMetricsSummaryVO summary() {
        TaggedCountVO memoryInjections = taggedCounts(MEMORY_INJECTIONS, "result");
        long memoryHits = value(memoryInjections, "hit");
        long memoryMisses = value(memoryInjections, "miss");

        return new AgentMetricsSummaryVO(
                OffsetDateTime.now(),
                toolMetrics(),
                tokenMetrics(),
                new MemoryMetricsVO(
                        memoryHits,
                        memoryMisses,
                        memoryInjections.total(),
                        sumCounters(MEMORY_ITEMS),
                        ratio(memoryHits, memoryInjections.total())),
                taggedCounts(TURNS, "status"),
                taggedCounts(CONFIRMATIONS, "decision"),
                taggedCounts(TASK_STEPS, "status"),
                taggedCounts(RECOVERIES, "result"),
                taggedCounts(SECURITY_BLOCKS, "reason"),
                latencyMetrics(FIRST_TOKEN_DURATION),
                latencyMetrics(TURN_DURATION));
    }

    @Override
    public AgentMetricsPeriodVO periodSummary(int days) {
        AgentProperties.Observability obs = agentProperties.getObservability();
        int window = Math.min(Math.max(days, 1), Math.max(1, obs.getPeriodMaxDays()));
        OffsetDateTime end = OffsetDateTime.now();
        OffsetDateTime start = end.minusDays(window);
        List<AgentTurnTraceEntity> rows = agentTurnTraceMapper.selectList(
                new LambdaQueryWrapper<AgentTurnTraceEntity>()
                        .ge(AgentTurnTraceEntity::getCreatedAt, start)
                        .le(AgentTurnTraceEntity::getCreatedAt, end));

        long turns = rows.size();
        long done = 0;
        long errors = 0;
        long timeouts = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        long totalTokens = 0;
        long firstTokenSamples = 0;
        long firstTokenTotalMs = 0;
        long turnSamples = 0;
        long turnTotalMs = 0;
        long toolCallsTotal = 0;
        long toolCallsSuccess = 0;
        Map<String, long[]> providers = new LinkedHashMap<>();

        for (AgentTurnTraceEntity row : rows) {
            switch (row.getStatus() == null ? "" : row.getStatus()) {
                case AgentTurnTraceEntity.STATUS_DONE -> done++;
                case AgentTurnTraceEntity.STATUS_ERROR -> errors++;
                case AgentTurnTraceEntity.STATUS_TIMEOUT -> timeouts++;
                default -> {
                }
            }
            promptTokens += row.getPromptTokens() == null ? 0 : row.getPromptTokens();
            completionTokens += row.getCompletionTokens() == null ? 0 : row.getCompletionTokens();
            totalTokens += row.getTotalTokens() == null ? 0 : row.getTotalTokens();
            if (row.getFirstTokenMs() != null && row.getFirstTokenMs() >= 0) {
                firstTokenSamples++;
                firstTokenTotalMs += row.getFirstTokenMs();
            }
            if (row.getTotalMs() != null && row.getTotalMs() >= 0) {
                turnSamples++;
                turnTotalMs += row.getTotalMs();
            }
            toolCallsTotal += countToolCalls(row, false);
            toolCallsSuccess += countToolCalls(row, true);
            String providerKey = StringUtils.hasText(row.getProviderCode()) ? row.getProviderCode() : "unknown";
            long[] stat = providers.computeIfAbsent(providerKey, ignored -> new long[2]);
            stat[0]++;
            stat[1] += row.getTotalTokens() == null ? 0 : row.getTotalTokens();
        }

        List<AgentMetricsPeriodVO.ProviderStatVO> providerStats = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : providers.entrySet()) {
            providerStats.add(new AgentMetricsPeriodVO.ProviderStatVO(
                    entry.getKey(), "", entry.getValue()[0], entry.getValue()[1]));
        }

        Double avgFirstToken = firstTokenSamples == 0 ? null : (double) firstTokenTotalMs / firstTokenSamples;
        Double avgTotal = turnSamples == 0 ? null : (double) turnTotalMs / turnSamples;
        Double toolRate = toolCallsTotal == 0 ? null : (double) toolCallsSuccess / toolCallsTotal;

        double inputRate = agentProperties.getInputCostPerThousandTokens();
        double outputRate = agentProperties.getOutputCostPerThousandTokens();
        boolean costConfigured = inputRate > 0 || outputRate > 0;
        double cost = promptTokens / 1000.0 * inputRate + completionTokens / 1000.0 * outputRate;

        return new AgentMetricsPeriodVO(window, start.toString(), end.toString(), turns, done, errors, timeouts,
                promptTokens, completionTokens, totalTokens, avgFirstToken, avgTotal,
                toolCallsTotal, toolCallsSuccess, toolRate, costConfigured, cost,
                List.copyOf(providerStats), OffsetDateTime.now().toString());
    }

    /**
     * Counts tool calls inside one turn's {@code tool_calls_json}. When {@code successOnly} is
     * false the total is returned instead; unknown layouts degrade to zero.
     */
    private long countToolCalls(AgentTurnTraceEntity row, boolean successOnly) {
        if (!StringUtils.hasText(row.getToolCallsJson())) {
            return 0;
        }
        try {
            JsonNode nodes = objectMapper.readTree(row.getToolCallsJson());
            if (!nodes.isArray()) {
                return 0;
            }
            long count = 0;
            for (JsonNode node : nodes) {
                if (!successOnly) {
                    count++;
                } else if (node.path("ok").asBoolean(false) || "SUCCESS".equalsIgnoreCase(node.path("status").asText(""))) {
                    count++;
                }
            }
            return count;
        } catch (Exception exception) {
            return 0;
        }
    }

    private ToolMetricsVO toolMetrics() {
        Collection<Counter> counters = meterRegistry.find(TOOL_CALLS).counters();
        Set<String> tools = new TreeSet<>();
        for (Counter counter : counters) {
            tools.add(tag(counter, "tool"));
        }

        List<ToolBreakdownVO> breakdown = new ArrayList<>();
        long totalSuccess = 0L;
        long totalFailure = 0L;
        for (String tool : tools) {
            long success = sumCounters(counters, "tool", tool, "outcome", "success");
            long failure = sumCounters(counters, "tool", tool, "outcome", "failure");
            long total = success + failure;
            TimerAggregate duration = timerAggregate(TOOL_DURATION, "tool", tool);
            breakdown.add(new ToolBreakdownVO(
                    tool,
                    total,
                    success,
                    failure,
                    ratio(success, total),
                    duration.averageMs()));
            totalSuccess += success;
            totalFailure += failure;
        }

        long total = totalSuccess + totalFailure;
        TimerAggregate duration = timerAggregate(TOOL_DURATION, null, null);
        return new ToolMetricsVO(
                total,
                totalSuccess,
                totalFailure,
                ratio(totalSuccess, total),
                duration.averageMs(),
                breakdown);
    }

    private TokenMetricsVO tokenMetrics() {
        long prompt = sumCounters(TOKENS, "direction", "prompt");
        long completion = sumCounters(TOKENS, "direction", "completion");
        long total = sumCounters(TOKENS, "direction", "total");
        double cost = meterRegistry.find(TOKEN_COST).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
        boolean costConfigured = agentProperties.getInputCostPerThousandTokens() > 0.0
                || agentProperties.getOutputCostPerThousandTokens() > 0.0;
        return new TokenMetricsVO(prompt, completion, total, cost, costConfigured);
    }

    private TaggedCountVO taggedCounts(String metricName, String tagName) {
        Map<String, Long> values = new TreeMap<>();
        for (Counter counter : meterRegistry.find(metricName).counters()) {
            values.merge(tag(counter, tagName), count(counter), Long::sum);
        }
        long total = values.values().stream().mapToLong(Long::longValue).sum();
        return new TaggedCountVO(total, values);
    }

    private LatencyMetricsVO latencyMetrics(String metricName) {
        TimerAggregate aggregate = timerAggregate(metricName, null, null);
        return new LatencyMetricsVO(aggregate.count(), aggregate.averageMs(), aggregate.maxMs());
    }

    private TimerAggregate timerAggregate(String metricName, String tagName, String tagValue) {
        long count = 0L;
        double totalMs = 0.0;
        double maxMs = 0.0;
        for (Timer timer : meterRegistry.find(metricName).timers()) {
            if (tagName != null && !tagValue.equals(tag(timer, tagName))) {
                continue;
            }
            count += timer.count();
            totalMs += timer.totalTime(TimeUnit.MILLISECONDS);
            maxMs = Math.max(maxMs, timer.max(TimeUnit.MILLISECONDS));
        }
        return new TimerAggregate(count, totalMs, maxMs);
    }

    private long sumCounters(String metricName) {
        return meterRegistry.find(metricName).counters().stream()
                .mapToLong(this::count)
                .sum();
    }

    private long sumCounters(String metricName, String tagName, String tagValue) {
        return meterRegistry.find(metricName).counters().stream()
                .filter(counter -> tagValue.equals(tag(counter, tagName)))
                .mapToLong(this::count)
                .sum();
    }

    private long sumCounters(
            Collection<Counter> counters,
            String firstTagName,
            String firstTagValue,
            String secondTagName,
            String secondTagValue) {
        return counters.stream()
                .filter(counter -> firstTagValue.equals(tag(counter, firstTagName)))
                .filter(counter -> secondTagValue.equals(tag(counter, secondTagName)))
                .mapToLong(this::count)
                .sum();
    }

    private long count(Counter counter) {
        return Math.round(counter.count());
    }

    private String tag(Counter counter, String tagName) {
        String value = counter.getId().getTag(tagName);
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private String tag(Timer timer, String tagName) {
        String value = timer.getId().getTag(tagName);
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private long value(TaggedCountVO counts, String key) {
        return counts.values().getOrDefault(key, 0L);
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0L ? 0.0 : (double) numerator / denominator;
    }

    private record TimerAggregate(long count, double totalMs, double maxMs) {

        private double averageMs() {
            return count == 0L ? 0.0 : totalMs / count;
        }
    }
}
