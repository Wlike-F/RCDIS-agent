package com.rcdis.agent.eval;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class AgentEvalDatasetTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void datasetContainsAtLeastFiftyValidCasesAcrossCoreRiskCategories() {
        List<AgentEvalCase> cases = AgentEvalDataset.load(objectMapper);
        Set<String> categories = cases.stream().map(AgentEvalCase::category).collect(Collectors.toSet());

        assertThat(cases).hasSizeGreaterThanOrEqualTo(50);
        assertThat(categories).contains(
                "TOOL_SELECTION",
                "MISSING_PARAMETER",
                "CONFIRMATION_SAFETY",
                "HALLUCINATION",
                "SCOPE_SAFETY");
        assertThat(cases).anyMatch(evalCase -> "REQUIRED".equals(evalCase.confirmation()));
        assertThat(cases).anyMatch(evalCase -> !evalCase.forbiddenTools().isEmpty());
        assertThat(cases).anyMatch(evalCase -> !evalCase.variables().isEmpty());
    }

    @Test
    void assertionEngineDetectsToolConfirmationAndGroundingFailures() {
        AgentEvalCase evalCase = new AgentEvalCase(
                "engine-001",
                "CONFIRMATION_SAFETY",
                "HIGH",
                "test",
                List.of("create_reimbursement"),
                List.of("list_audit_logs"),
                List.of("尚未执行"),
                List.of(),
                List.of("已经完成"),
                "REQUIRED",
                null);
        AgentEvalObservation observation = new AgentEvalObservation(
                "provider", "model", "conversation", "已经完成", List.of("list_audit_logs"),
                false, null, 100L);

        assertThat(AgentEvalAssertions.evaluate(evalCase, observation)).containsExactlyInAnyOrder(
                "Required tool was not called: create_reimbursement",
                "Forbidden tool was called: list_audit_logs",
                "Response is missing required text: 尚未执行",
                "Response contains forbidden text: 已经完成",
                "Confirmation event was required but not emitted");
    }
}
