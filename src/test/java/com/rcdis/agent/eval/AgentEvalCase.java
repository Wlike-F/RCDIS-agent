package com.rcdis.agent.eval;

import java.util.List;
import java.util.Map;

/** One version-controlled black-box Agent evaluation case. */
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
        Map<String, String> variables
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

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
