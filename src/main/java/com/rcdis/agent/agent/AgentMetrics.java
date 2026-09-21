package com.rcdis.agent.agent;

import org.springframework.stereotype.Component;

import com.rcdis.agent.config.AgentProperties;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;

/** Business-level metrics for Agent decisions, tools, memory and execution safety. */
@Component
@RequiredArgsConstructor
public class AgentMetrics {

    private final MeterRegistry registry;
    private final AgentProperties agentProperties;

    public void recordTool(String toolName, boolean success, long durationMs) {
        String outcome = success ? "success" : "failure";
        Counter.builder("rcdis_agent_tool_calls_total")
                .tag("tool", bounded(toolName))
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        Timer.builder("rcdis_agent_tool_duration")
                .tag("tool", bounded(toolName))
                .tag("outcome", outcome)
                .register(registry)
                .record(Math.max(0L, durationMs), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void recordTurn(String provider, String model, String status, TurnTraceCollector collector) {
        Counter.builder("rcdis_agent_turns_total")
                .tag("provider", bounded(provider))
                .tag("model", bounded(model))
                .tag("status", bounded(status))
                .register(registry)
                .increment();
        incrementTokens("prompt", provider, model, collector.promptTokens());
        incrementTokens("completion", provider, model, collector.completionTokens());
        incrementTokens("total", provider, model, collector.totalTokens());
        double cost = cost(collector.promptTokens(), agentProperties.getInputCostPerThousandTokens())
                + cost(collector.completionTokens(), agentProperties.getOutputCostPerThousandTokens());
        if (cost > 0) {
            Counter.builder("rcdis_agent_token_cost_estimate_total")
                    .tag("provider", bounded(provider))
                    .tag("model", bounded(model))
                    .register(registry)
                    .increment(cost);
        }
        if (collector.firstTokenMs() != null) {
            Timer.builder("rcdis_agent_first_token_duration")
                    .tag("provider", bounded(provider))
                    .tag("model", bounded(model))
                    .register(registry)
                    .record(collector.firstTokenMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        Timer.builder("rcdis_agent_turn_duration")
                .tag("provider", bounded(provider))
                .tag("model", bounded(model))
                .tag("status", bounded(status))
                .register(registry)
                .record(collector.totalMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void recordMemoryInjection(int loadedCount) {
        Counter.builder("rcdis_agent_memory_injection_total")
                .tag("result", loadedCount > 0 ? "hit" : "miss")
                .register(registry)
                .increment();
        if (loadedCount > 0) {
            Counter.builder("rcdis_agent_memory_items_total")
                    .register(registry)
                    .increment(loadedCount);
        }
    }

    public void recordConfirmation(String decision) {
        Counter.builder("rcdis_agent_confirmations_total")
                .tag("decision", bounded(decision))
                .register(registry)
                .increment();
    }

    public void recordTaskStep(String status) {
        Counter.builder("rcdis_agent_task_steps_total")
                .tag("status", bounded(status))
                .register(registry)
                .increment();
    }

    public void recordRecovery(String result) {
        Counter.builder("rcdis_agent_recoveries_total")
                .tag("result", bounded(result))
                .register(registry)
                .increment();
    }

    public void recordSecurityBlock(String reason) {
        Counter.builder("rcdis_agent_security_blocks_total")
                .tag("reason", bounded(reason))
                .register(registry)
                .increment();
    }

    private void incrementTokens(String direction, String provider, String model, Integer value) {
        if (value == null || value < 0) return;
        Counter.builder("rcdis_agent_tokens_total")
                .tag("direction", direction)
                .tag("provider", bounded(provider))
                .tag("model", bounded(model))
                .register(registry)
                .increment(value);
    }

    private double cost(Integer tokens, double ratePerThousand) {
        return tokens == null || tokens < 0 ? 0.0 : tokens / 1000.0 * ratePerThousand;
    }

    private String bounded(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
