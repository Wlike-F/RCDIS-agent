package com.rcdis.agent.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.rcdis.agent.to.ToolCallTraceTO;

/**
 * Mutable, per-turn accumulator for observability data <b>and runtime self-healing state</b>.
 *
 * <p>Tool callbacks run on Reactor threads while the turn finishes on the SSE worker thread, so all
 * mutators are synchronized. The collector is created at turn start, fed by {@link ToolReporting}
 * (tool chain) and the streaming pipeline (first-token time + token usage), then flushed to
 * {@code agent_turn_trace} once at turn end.</p>
 *
 * <p>Beyond observability, the collector carries the runtime halt state used by
 * {@link RecordingToolCallback} to implement the three-tier circuit breaker:
 * <ul>
 *   <li>{@code failureCountByTool} — how many times a given tool returned a hard failure this turn;</li>
 *   <li>{@code lastInputHashByTool} + {@code stallCountByTool} — detects the "same tool, same args"
 *       retry loop that pure failure counting misses;</li>
 *   <li>{@code frozenTools} — set once a tool crosses {@code freezeThreshold} or the turn ceiling,
 *       so subsequent invocations short-circuit inside the callback;</li>
 *   <li>{@code haltReason} + {@code pendingNeedsInput} — what gets surfaced as an
 *       {@code agent_needs_input} SSE event at turn end.</li>
 * </ul>
 * Lifecycle: the collector lives exactly one turn. Frozen state therefore naturally expires between
 * turns without any extra bookkeeping.</p>
 */
public final class TurnTraceCollector {

    private final long startNanos = System.nanoTime();
    private final List<ToolCallTraceTO> toolCalls = new ArrayList<>();
    private final Map<String, Long> toolStartNanos = new HashMap<>();

    // Runtime halt state, all guarded by the monitor of this instance.
    private final Map<String, Integer> failureCountByTool = new HashMap<>();
    private final Map<String, String> lastInputHashByTool = new HashMap<>();
    private final Map<String, Integer> stallCountByTool = new HashMap<>();
    private final Set<String> frozenTools = new HashSet<>();
    private final List<Map<String, Object>> pendingNeedsInput = new ArrayList<>();

    private Long firstTokenMs;
    private long endNanos;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private boolean finished;
    private int totalToolCalls;
    private String haltReason;

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

    // ---------- runtime halt state (L1 detection + L2 intervention) ----------

    /**
     * Records one tool invocation outcome and returns the tier the callback should apply.
     * The caller (RecordingToolCallback) has already classified the result via
     * {@link ToolResultAnalyzer}; this method only updates bookkeeping and selects the tier
     * from the supplied thresholds so they remain runtime-configurable.
     *
     * @param toolName           registered tool name
     * @param inputHash          normalized hash of the tool arguments, used to detect "same retry"
     * @param failed             {@code true} when the call is a hard failure or a silent-empty
     * @param hardHaltThreshold  severity at which the callback should emit {@code HARD_HALT}
     * @param freezeThreshold    severity at which the callback should emit {@code FREEZE}
     * @return the tier to apply, computed from the newly-updated counters and thresholds
     */
    public synchronized HaltTier registerOutcome(
            String toolName, String inputHash, boolean failed,
            int hardHaltThreshold, int freezeThreshold) {
        totalToolCalls++;
        if (!failed) {
            failureCountByTool.put(toolName, 0);
            stallCountByTool.put(toolName, 0);
            lastInputHashByTool.put(toolName, inputHash);
            return HaltTier.OK;
        }
        int failures = failureCountByTool.merge(toolName, 1, Integer::sum);
        String previousHash = lastInputHashByTool.get(toolName);
        boolean repeatedInput = previousHash != null && previousHash.equals(inputHash);
        int stalls = repeatedInput ? stallCountByTool.merge(toolName, 1, Integer::sum) : 1;
        lastInputHashByTool.put(toolName, inputHash);
        // Tier selection uses the max of "failures" and "stalls": either metric crossing the
        // threshold signals the model is not making progress. Thresholds of 0 disable the tier.
        int severity = Math.max(failures, stalls);
        if (freezeThreshold > 0 && severity >= freezeThreshold) {
            return HaltTier.FREEZE;
        }
        if (hardHaltThreshold > 0 && severity >= hardHaltThreshold) {
            return HaltTier.HARD_HALT;
        }
        return HaltTier.SOFT_HINT;
    }

    /** Incremental ceiling check for total tool calls in one turn, independent of failures. */
    public synchronized boolean isTurnCeilingReached(int ceiling) {
        return ceiling > 0 && totalToolCalls > ceiling;
    }

    public synchronized boolean isFrozen(String toolName) {
        return frozenTools.contains(toolName);
    }

    public synchronized void freeze(String toolName) {
        frozenTools.add(toolName);
    }

    public synchronized Set<String> frozenToolsView() {
        return Set.copyOf(frozenTools);
    }

    public synchronized int failureCount(String toolName) {
        return failureCountByTool.getOrDefault(toolName, 0);
    }

    public synchronized int stallCount(String toolName) {
        return stallCountByTool.getOrDefault(toolName, 0);
    }

    public synchronized int totalToolCalls() {
        return totalToolCalls;
    }

    /**
     * Records a needs-input payload to be pushed once at turn end. Multiple halts may accumulate;
     * the pipeline emits them in insertion order so a turn that halts two tools surfaces both.
     */
    public synchronized void queueNeedsInput(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        pendingNeedsInput.add(new LinkedHashMap<>(payload));
    }

    public synchronized List<Map<String, Object>> drainNeedsInput() {
        if (pendingNeedsInput.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> drained = new ArrayList<>(pendingNeedsInput);
        pendingNeedsInput.clear();
        return drained;
    }

    public synchronized void setHaltReason(String reason) {
        this.haltReason = reason;
    }

    public synchronized String haltReason() {
        return haltReason;
    }

    /** Aggregate stall severity across every tool; the trace uses it to answer "did this turn halt". */
    public synchronized int haltCount() {
        int total = 0;
        for (int value : failureCountByTool.values()) {
            total += value;
        }
        return total;
    }

    private long elapsedMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** Tier returned by {@link #registerOutcome}, consumed by {@link RecordingToolCallback}. */
    public enum HaltTier {
        /** Successful call: pass the delegate result through unchanged. */
        OK,
        /** First failure: keep the result but append a soft {@code haltHint}. */
        SOFT_HINT,
        /** Second failure or second identical retry: replace the result with a {@code halt} block. */
        HARD_HALT,
        /** Third failure or turn-ceiling breach: freeze the tool for the rest of the turn. */
        FREEZE
    }
}
