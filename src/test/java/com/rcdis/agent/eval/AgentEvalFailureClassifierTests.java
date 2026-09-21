package com.rcdis.agent.eval;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvalFailureClassifierTests {

    @Test
    void classifiesRequiredToolOmissionAndExtractsToolName() {
        List<AgentEvalFailureClassifier.ClassifiedFailure> classified =
                AgentEvalFailureClassifier.classify(List.of("Required tool was not called: list_projects"));
        assertThat(classified).hasSize(1);
        assertThat(classified.get(0).type()).isEqualTo(AgentEvalFailureType.TOOL_OMITTED);
        assertThat(classified.get(0).tool()).isEqualTo("list_projects");
        assertThat(classified.get(0).message()).isEqualTo("Required tool was not called: list_projects");
    }

    @Test
    void classifiesForbiddenToolInvocationAndExtractsToolName() {
        List<AgentEvalFailureClassifier.ClassifiedFailure> classified =
                AgentEvalFailureClassifier.classify(List.of("Forbidden tool was called: submit_reimbursement"));
        assertThat(classified).hasSize(1);
        assertThat(classified.get(0).type()).isEqualTo(AgentEvalFailureType.TOOL_FORBIDDEN_INVOKED);
        assertThat(classified.get(0).tool()).isEqualTo("submit_reimbursement");
    }

    @Test
    void classifiesConfirmationGateFailuresOnBothDirections() {
        List<AgentEvalFailureClassifier.ClassifiedFailure> classified = AgentEvalFailureClassifier.classify(List.of(
                "Confirmation event was required but not emitted",
                "Confirmation event was forbidden but emitted"));
        assertThat(classified)
                .allSatisfy(failure -> assertThat(failure.type()).isEqualTo(AgentEvalFailureType.CONFIRMATION_GATE))
                .allSatisfy(failure -> assertThat(failure.tool()).isNull());
    }

    @Test
    void classifiesTextAssertionFailuresAsFactInconsistency() {
        List<AgentEvalFailureClassifier.ClassifiedFailure> classified = AgentEvalFailureClassifier.classify(List.of(
                "Response is missing required text: 113.05",
                "Response contains none of: [未找到, 不存在]",
                "Response contains forbidden text: 我猜"));
        assertThat(classified)
                .allSatisfy(failure -> assertThat(failure.type()).isEqualTo(AgentEvalFailureType.FACT_INCONSISTENCY))
                .allSatisfy(failure -> assertThat(failure.tool()).isNull());
    }

    @Test
    void classifiesAgentErrorAndFallsBackToUnknown() {
        assertThat(AgentEvalFailureClassifier.classify(List.of("Agent returned error: upstream timeout"))
                .get(0).type()).isEqualTo(AgentEvalFailureType.RUNTIME_ERROR);
        assertThat(AgentEvalFailureClassifier.classify(List.of("Some future failure kind"))
                .get(0).type()).isEqualTo(AgentEvalFailureType.UNKNOWN);
    }

    @Test
    void emptyOrNullFailureListYieldsNoClassifications() {
        assertThat(AgentEvalFailureClassifier.classify(List.of())).isEmpty();
        assertThat(AgentEvalFailureClassifier.classify(null)).isEmpty();
    }
}
