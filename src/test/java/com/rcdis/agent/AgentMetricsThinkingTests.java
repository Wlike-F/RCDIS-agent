package com.rcdis.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rcdis.agent.agent.AgentMetrics;
import com.rcdis.agent.config.AgentProperties;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Covers the thinking-channel counters and timer exposed on Prometheus. These are the only
 * observable artifacts of a reasoning turn on the server side (content itself is never stored);
 * operators use them to answer "which model streams thinking, and how long does it take".
 */
class AgentMetricsThinkingTests {

    private SimpleMeterRegistry registry;
    private AgentMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AgentMetrics(registry, new AgentProperties());
    }

    @Test
    void recordThinkingWithCharsIncrementsBothTurnAndCharCountersAndRecordsDuration() {
        metrics.recordThinking("dashscope", "qwen3-thinking", 480, 3200L);

        Counter turnCounter = registry.counter("rcdis_agent_thinking_turns_total",
                "provider", "dashscope", "model", "qwen3-thinking", "present", "yes");
        Counter charCounter = registry.counter("rcdis_agent_thinking_chars_total",
                "provider", "dashscope", "model", "qwen3-thinking");
        Timer timer = registry.timer("rcdis_agent_thinking_duration",
                "provider", "dashscope", "model", "qwen3-thinking");

        assertThat(turnCounter.count()).isEqualTo(1.0);
        assertThat(charCounter.count()).isEqualTo(480.0);
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(3200L);
    }

    @Test
    void recordThinkingWithoutCharsTagsAsAbsentAndSkipsTimer() {
        metrics.recordThinking("dashscope", "qwen-plus", 0, 0L);

        Counter present = registry.counter("rcdis_agent_thinking_turns_total",
                "provider", "dashscope", "model", "qwen-plus", "present", "no");
        Counter chars = registry.find("rcdis_agent_thinking_chars_total").counter();
        Timer timer = registry.find("rcdis_agent_thinking_duration").timer();

        assertThat(present.count()).isEqualTo(1.0);
        // Non-thinking turns must not skew the chars/timer aggregates.
        assertThat(chars).isNull();
        assertThat(timer).isNull();
    }

    @Test
    void recordThinkingTruncatesOverlongTagValues() {
        String longModel = "m".repeat(120);
        metrics.recordThinking("p", longModel, 10, 100L);

        // AgentMetrics.bounded() clamps values to 64 chars; if the truncation was missing, the
        // registry would keep a 120-char tag and the counter lookup below would return zero.
        String truncated = longModel.substring(0, 64);
        Counter counter = registry.find("rcdis_agent_thinking_chars_total")
                .tag("model", truncated).counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(10.0);
    }
}
