package com.rcdis.agent.eval;

import java.util.List;
import java.util.Map;

/**
 * One version-controlled black-box Agent evaluation case.
 *
 * <p>The two halt-related fields back the runtime circuit breaker cases ({@code STALL_LOOP} /
 * {@code GRACEFUL_EXIT}); they are optional so every pre-existing case in
 * {@code agent-eval/cases.jsonl} continues to parse unchanged.</p>
 */
public record AgentEvalCase(
        String id,
        String category,
        String riskLevel,
        String input,
        List<String> requiredTools,
        List<String> forbiddenTools,
        List<String> mustContain,
        List<String> mustContainAny,
        List<String> mustNotContain,
        String confirmation,
        Map<String, String> variables,
        /** Max repeat count allowed for any single tool before the STALL_LOOP check fails. */
        Integer maxToolRepeat,
        /** When true the pipeline must emit {@code agent_needs_input}; when false, must NOT. */
        Boolean requiresNeedsInput
) {

    public AgentEvalCase {
        requiredTools = safeList(requiredTools);
        forbiddenTools = safeList(forbiddenTools);
        mustContain = safeList(mustContain);
        mustContainAny = safeList(mustContainAny);
        mustNotContain = safeList(mustNotContain);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        confirmation = confirmation == null ? "OPTIONAL" : confirmation;
    }

    /** Pre-halt constructor kept so existing tests and JSONL cases with 11 columns still parse. */
    public AgentEvalCase(
            String id,
            String category,
            String riskLevel,
            String input,
            List<String> requiredTools,
            List<String> forbiddenTools,
            List<String> mustContain,
            List<String> mustContainAny,
            List<String> mustNotContain,
            String confirmation,
            Map<String, String> variables
    ) {
        this(id, category, riskLevel, input, requiredTools, forbiddenTools, mustContain,
                mustContainAny, mustNotContain, confirmation, variables, null, null);
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
