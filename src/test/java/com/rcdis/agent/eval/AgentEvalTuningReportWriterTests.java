package com.rcdis.agent.eval;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvalTuningReportWriterTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentEvalResult result(String caseId, String category, String riskLevel, String status,
            List<String> failures, List<String> tools) {
        AgentEvalObservation observation = new AgentEvalObservation(
                "dashscope", "test-model", "conv-1", "回答文本", tools, false, null, 1200L);
        return new AgentEvalResult(caseId, category, riskLevel, "dashscope", "test-model",
                status, failures, observation);
    }

    @Test
    void writesManifestAndReportWithClustersAndBlockingRegression(@TempDir Path tempDir) throws IOException {
        List<AgentEvalResult> results = List.of(
                result("tool-budget-001", "TOOL_SELECTION", "HIGH", "FAILED",
                        List.of("Required tool was not called: query_project_budget"), List.of("list_projects")),
                result("safety-bypass-confirm-001", "CONFIRMATION_SAFETY", "CRITICAL", "FAILED",
                        List.of("Confirmation event was required but not emitted"), List.of()),
                result("scope-weather-001", "SCOPE_SAFETY", "LOW", "PASSED", List.of(), List.of()));
        AgentEvalRunManifest current = AgentEvalRunManifest.from("abc1234", "f".repeat(64),
                "prompts/agent-system.st", List.of("dashscope"), results);
        AgentEvalRunManifest baseline = new AgentEvalRunManifest("earlier-run", "abc1234", "0".repeat(64),
                "prompts/agent-system.st", List.of("dashscope"), 3, 2, 0, new BigDecimal("0.6667"),
                List.of(new AgentEvalRunManifest.CaseRecord("safety-bypass-confirm-001",
                        "CONFIRMATION_SAFETY", "CRITICAL", "dashscope", "PASSED", List.of(), List.of())));

        AgentEvalTuningReportWriter.write(objectMapper, tempDir, current, baseline,
                AgentEvalPromptAnchor.load("prompts/agent-system.st"),
                AgentEvalTuningMap.load(objectMapper), results);

        String report = Files.readString(tempDir.resolve("agent-eval-tuning.md"));
        assertThat(report).contains("Agent Eval 调优建议报告");
        assertThat(report).contains("阻断晋级");
        assertThat(report).contains("safety-bypass-confirm-001");
        assertThat(report).contains("@tunable:tool-selection");
        assertThat(report).contains("Required tool was not called: query_project_budget");
        assertThat(report).contains("漏调必调工具");
        assertThat(report).contains("确认门违规");
        assertThat(report).contains("f".repeat(64));
        assertThat(report).contains("+");

        String manifestJson = Files.readString(tempDir.resolve("agent-eval-run.json"));
        assertThat(manifestJson).contains("\"promptSha256\"");
        assertThat(manifestJson).contains("query_project_budget");
        assertThat(manifestJson).contains("\"skipped\" : 0");
    }

    @Test
    void reportWithoutBaselineAndWithoutFailuresSaysNoTuningNeeded(@TempDir Path tempDir) throws IOException {
        List<AgentEvalResult> results = List.of(
                result("tool-date-now-001", "TOOL_SELECTION", "LOW", "PASSED",
                        List.of(), List.of("get_current_datetime")));
        AgentEvalRunManifest manifest = AgentEvalRunManifest.from("unknown", "a".repeat(64),
                "prompts/agent-system.st", List.of("dashscope"), results);

        AgentEvalTuningReportWriter.write(objectMapper, tempDir, manifest, null,
                AgentEvalPromptAnchor.load("prompts/agent-system.st"),
                AgentEvalTuningMap.load(objectMapper), results);

        String report = Files.readString(tempDir.resolve("agent-eval-tuning.md"));
        assertThat(report).contains("无需调优");
        assertThat(report).doesNotContain("阻断晋级");
        assertThat(report).doesNotContain("基线通过率");
        assertThat(report).doesNotContain("@tunable:");
    }

    @Test
    void manifestSkipsExcludedCasesFromPassRate(@TempDir Path tempDir) throws IOException {
        List<AgentEvalResult> results = List.of(
                result("confirm-create-001", "CONFIRMATION_SAFETY", "CRITICAL", "PASSED", List.of(), List.of()),
                result("missing-create-project-001", "MISSING_PARAMETER", "HIGH", "SKIPPED",
                        List.of(), List.of()));
        AgentEvalRunManifest manifest = AgentEvalRunManifest.from("abc1234", "b".repeat(64),
                "prompts/agent-system.st", List.of("dashscope"), results);

        assertThat(manifest.executed()).isEqualTo(1);
        assertThat(manifest.passed()).isEqualTo(1);
        assertThat(manifest.skipped()).isEqualTo(1);
        assertThat(manifest.passRate()).isEqualByComparingTo(new BigDecimal("1.0000"));
    }
}
