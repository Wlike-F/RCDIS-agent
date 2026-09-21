package com.rcdis.agent.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.rcdis.agent.to.ToolCallTraceTO;

/**
 * Mutable, per-turn accumulator for observability data.
 *
 * <p>Tool callbacks run on Reactor threads while the turn finishes on the SSE worker thread, so all
 * mutators are synchronized. The collector is created at turn start, fed by {@link ToolReporting}
 * (tool chain) and the streaming pipeline (first-token time + token usage), then flushed to
 * {@code agent_turn_trace} once at turn end.</p>
 */
public final class TurnTraceCollector {

    private final long startNanos = System.nanoTime();
    private final List<ToolCallTraceTO> toolCalls = new ArrayList<>();
    private final Map<String, Long> toolStartNanos = new HashMap<>();

    private Long firstTokenMs;
    private long endNanos;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private boolean finished;

    public synchronized void markFirstToken() {
        if (firstTokenMs == null) {
            firstTokenMs = elapsedMs();
        }
    }

    public synchronized void toolStart(String name) {
        toolStartNanos.put(name, System.nanoTime());
    }

    public synchronized void toolResult(String name, boolean ok) {
        Long start = toolStartNanos.remove(name);
        long durationMs = start == null ? 0L : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        toolCalls.add(new ToolCallTraceTO(name, ok, durationMs, ok ? "DONE" : "FAILED"));
    }

    public synchronized void setUsage(Integer prompt, Integer completion, Integer total) {
        // Streaming providers usually report usage only on the final chunk; keep the last non-null.
        if (prompt != null) {
            this.promptTokens = prompt;
        }
        if (completion != null) {
            this.completionTokens = completion;
        }
        if (total != null) {
            this.totalTokens = total;
        }
    }

    public synchronized void finish() {
        if (!finished) {
            endNanos = System.nanoTime();
            finished = true;
        }
    }

    public synchronized Long firstTokenMs() {
        return firstTokenMs;
    }

    public synchronized long totalMs() {
        long end = finished ? endNanos : System.nanoTime();
        return TimeUnit.NANOSECONDS.toMillis(end - startNanos);
    }

    public synchronized Integer promptTokens() {
        return promptTokens;
    }

    public synchronized Integer completionTokens() {
        return completionTokens;
    }

    public synchronized Integer totalTokens() {
        return totalTokens;
    }

    public synchronized List<ToolCallTraceTO> toolCalls() {
        return List.copyOf(toolCalls);
    }

    private long elapsedMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
