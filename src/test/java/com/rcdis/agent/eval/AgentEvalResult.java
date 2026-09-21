package com.rcdis.agent.eval;

import java.util.List;

/** Assertion outcome for one case/provider pair. */
public record AgentEvalResult(
        String caseId,
        String category,
        String riskLevel,
        String providerId,
        String modelName,
        String status,
        List<String> failures,
        AgentEvalObservation observation
) {

    public AgentEvalResult {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }
}
