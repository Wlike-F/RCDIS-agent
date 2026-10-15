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
 *
 * <p>The trailing block carries business-state evidence: how many proposals one turn produced,
 * which confirmation id the user could act on, and (only when a case opts into confirmation) the
 * outcome of replaying that confirmation and the order's final status. Asserting on business state
 * rather than prose is what catches "the answer looked right but nothing was written twice".</p>
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
        Map<String, Integer> toolInvocationCounts,
        /** Number of {@code requires_confirmation} events this turn emitted (one turn may propose twice). */
        int confirmationEvents,
        /** Id of the first proposal emitted, or null when the turn proposed nothing. */
        String confirmationId,
        /** Whether a replayed confirmation executed again; null when the case did not replay. */
        Boolean replayExecuted,
        /** Pending-action status returned by the replay call. */
        String replayStatus,
        /** Business status read back after confirmation, e.g. draft / submitted. */
        String observedFinalStatus
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
                error, durationMs, false, Map.of(), 0, null, null, null, null);
    }

    /** Constructor used by the SSE parser, before any business-state probe has run. */
    public AgentEvalObservation(
            String providerId,
            String modelName,
            String conversationId,
            String response,
            List<String> tools,
            boolean confirmationRequested,
            String error,
            long durationMs,
            boolean needsInputEmitted,
            Map<String, Integer> toolInvocationCounts,
            int confirmationEvents,
            String confirmationId
    ) {
        this(providerId, modelName, conversationId, response, tools, confirmationRequested, error,
                durationMs, needsInputEmitted, toolInvocationCounts, confirmationEvents, confirmationId,
                null, null, null);
    }

    /** Returns a copy carrying the results of the confirmation-replay and final-state probes. */
    public AgentEvalObservation withBusinessState(Boolean replayExecuted, String replayStatus,
            String observedFinalStatus) {
        return new AgentEvalObservation(providerId, modelName, conversationId, response, tools,
                confirmationRequested, error, durationMs, needsInputEmitted, toolInvocationCounts,
                confirmationEvents, confirmationId, replayExecuted, replayStatus, observedFinalStatus);
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
