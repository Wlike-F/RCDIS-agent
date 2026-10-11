package com.rcdis.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Focused coverage for the reasoning-channel observability fields added to {@link TurnTraceCollector}.
 * Content is never retained on the collector; only counters and timestamps survive, which is what
 * the metrics layer reads.
 */
class TurnTraceCollectorThinkingTests {

    @Test
    void freshCollectorReportsNoThinking() {
        TurnTraceCollector collector = new TurnTraceCollector();
        assertThat(collector.thinkingChars()).isZero();
        assertThat(collector.firstThinkingMs()).isNull();
        assertThat(collector.lastThinkingMs()).isNull();
        assertThat(collector.thinkingDurationMs()).isZero();
    }

    @Test
    void recordThinkingAccumulatesCharsAndStampsFirstArrival() throws InterruptedException {
        TurnTraceCollector collector = new TurnTraceCollector();
        collector.recordThinking(120);
        Thread.sleep(15);
        collector.recordThinking(80);

        assertThat(collector.thinkingChars()).isEqualTo(200);
        assertThat(collector.firstThinkingMs()).isNotNull();
        assertThat(collector.lastThinkingMs()).isNotNull();
        assertThat(collector.lastThinkingMs())
                .isGreaterThanOrEqualTo(collector.firstThinkingMs());
        assertThat(collector.thinkingDurationMs()).isGreaterThanOrEqualTo(10L);
    }

    @Test
    void nonPositiveDeltaIsIgnored() {
        TurnTraceCollector collector = new TurnTraceCollector();
        collector.recordThinking(0);
        collector.recordThinking(-5);

        assertThat(collector.thinkingChars()).isZero();
        assertThat(collector.firstThinkingMs()).isNull();
    }

    @Test
    void thinkingFieldsAreIndependentOfVisibleTokenAccounting() {
        TurnTraceCollector collector = new TurnTraceCollector();
        collector.recordThinking(50);
        collector.markFirstToken();
        collector.recordThinking(30);

        assertThat(collector.thinkingChars()).isEqualTo(80);
        assertThat(collector.firstTokenMs()).isNotNull();
        // markFirstToken must not touch thinking timestamps and vice versa.
        assertThat(collector.firstThinkingMs()).isNotNull();
    }
}
