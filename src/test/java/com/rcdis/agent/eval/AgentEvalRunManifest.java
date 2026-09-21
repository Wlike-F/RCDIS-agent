package com.rcdis.agent.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Version-anchored manifest of one Agent evaluation run, persisted as {@code agent-eval-run.json}
 * so a later run can be compared against it (pass-rate delta, per-case regressions, prompt changes).
 */
public record AgentEvalRunManifest(
        String runId,
        String gitCommit,
        String promptSha256,
        String promptResource,
        List<String> providers,
        int executed,
        int passed,
        int skipped,
        BigDecimal passRate,
        List<CaseRecord> cases
) {

    /** Per-case outcome snapshot; failures keep the original assertion messages. */
    public record CaseRecord(
            String caseId,
            String category,
            String riskLevel,
            String providerId,
            String status,
            List<String> failures,
            List<String> tools
    ) {

        public CaseRecord {
            failures = failures == null ? List.of() : List.copyOf(failures);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    public AgentEvalRunManifest {
        providers = providers == null ? List.of() : List.copyOf(providers);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public static AgentEvalRunManifest from(String gitCommit, String promptSha256, String promptResource,
            List<String> providers, List<AgentEvalResult> results) {
        List<CaseRecord> cases = new ArrayList<>();
        int executed = 0;
        int passed = 0;
        int skipped = 0;
        for (AgentEvalResult result : results) {
            cases.add(new CaseRecord(result.caseId(), result.category(), result.riskLevel(), result.providerId(),
                    result.status(), result.failures(), result.observation().tools()));
            if ("SKIPPED".equals(result.status())) {
                skipped++;
            } else {
                executed++;
                if ("PASSED".equals(result.status())) {
                    passed++;
                }
            }
        }
        BigDecimal passRate = executed == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(passed).divide(BigDecimal.valueOf(executed), 4, RoundingMode.HALF_UP);
        return new AgentEvalRunManifest(OffsetDateTime.now().toString(), gitCommit, promptSha256,
                promptResource, providers, executed, passed, skipped, passRate, cases);
    }
}
