package com.rcdis.agent.eval;

import java.util.List;
import java.util.Map;

/**
 * Observable result of one live Agent turn.
 *
 * <p>{@code needsInputEmitted} and {@code tierCounts} back the STALL_LOOP / GRACEFUL_EXIT
 * categories added with the runtime circuit breaker: the former checks that a failing tool
 * stops being retried after two attempts, the latter that the pipeline surfaces a structured
 * halt event instead of silently giving up.</p>
 */
public record AgentEvalObservation(
        String providerId,
        String modelName,
        String conversationId,
        String response,
        List<String> tools,
        boolean confirmationRequested,
        String error,
        long durationMs,
        boolean needsInputEmitted,
        Map<String, Integer> toolInvocationCounts
) {

    public AgentEvalObservation {
        tools = tools == null ? List.of() : List.copyOf(tools);
        response = response == null ? "" : response;
        toolInvocationCounts = toolInvocationCounts == null ? Map.of() : Map.copyOf(toolInvocationCounts);
    }

    /** Compatibility constructor for tests that predate the halt observation fields. */
    public AgentEvalObservation(
            String providerId,
            String modelName,
            String conversationId,
            String response,
            List<String> tools,
            boolean confirmationRequested,
            String error,
            long durationMs
    ) {
        this(providerId, modelName, conversationId, response, tools, confirmationRequested,
                error, durationMs, false, Map.of());
    }

    /** Highest repeat count for any single tool this turn; used by STALL_LOOP assertions. */
    public int maxToolRepeat() {
        int max = 0;
        for (int value : toolInvocationCounts.values()) {
            if (value > max) {
                max = value;
            }
        }
        return max;
    }
}
