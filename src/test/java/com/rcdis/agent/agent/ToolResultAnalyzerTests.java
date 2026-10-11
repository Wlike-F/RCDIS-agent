package com.rcdis.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.rcdis.agent.agent.ToolResultAnalyzer.FailureKind;
import com.rcdis.agent.agent.ToolResultAnalyzer.Outcome;

/**
 * Unit coverage for the pure classifier powering {@link RecordingToolCallback}'s circuit breaker.
 * Lives in the same package because {@link ToolResultAnalyzer} is package-private by design.
 */
class ToolResultAnalyzerTests {

    @Test
    void hashInputNormalizesWhitespaceAndIsCaseInsensitive() {
        String a = ToolResultAnalyzer.hashInput("{\"projectCode\": \"P-1\"}");
        String b = ToolResultAnalyzer.hashInput("{  \"projectcode\" :  \"p-1\"  }");
        // Lower-case + strip whitespace means the same logical input maps to the same hash,
        // even though the raw JSON strings differ in casing and spacing. This is what lets the
        // circuit breaker distinguish "same retry" from "different query".
        assertThat(a).isEqualTo(b);
        // A materially different input yields a different hash.
        assertThat(ToolResultAnalyzer.hashInput("{\"x\":1}"))
                .isNotEqualTo(ToolResultAnalyzer.hashInput("{\"x\":2}"));
        // Whitespace-only variation still hashes the same after normalization.
        assertThat(ToolResultAnalyzer.hashInput("{\"x\": 1}"))
                .isEqualTo(ToolResultAnalyzer.hashInput("{\"x\":1}"));
    }

    @Test
    void hashInputOnBlankOrNullIsEmpty() {
        assertThat(ToolResultAnalyzer.hashInput(null)).isEmpty();
        assertThat(ToolResultAnalyzer.hashInput("")).isEmpty();
        assertThat(ToolResultAnalyzer.hashInput("   ")).isEmpty();
    }

    @Test
    void classifyDetectsExplicitOkFalseAsHardFailure() {
        Outcome outcome = ToolResultAnalyzer.classify(
                "query_project_budget", "{\"ok\":false,\"error\":\"未找到项目 P-1\"}", null);
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.kind()).isEqualTo(FailureKind.HARD_FAIL);
        assertThat(outcome.message()).contains("未找到项目");
    }

    @Test
    void classifyDetectsFailedStatusAsHardFailure() {
        Outcome outcome = ToolResultAnalyzer.classify(
                "get_receipt_ocr", "{\"status\":\"PENDING\",\"message\":\"still running\"}", null);
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.kind()).isEqualTo(FailureKind.HARD_FAIL);
    }

    @Test
    void classifyTreatsSilentEmptyRecordsAsFailureForReadTools() {
        Outcome outcome = ToolResultAnalyzer.classify(
                "list_reimbursements", "{\"ok\":true,\"records\":[]}", null);
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.kind()).isEqualTo(FailureKind.SILENT_EMPTY);
    }

    @Test
    void classifyIgnoresEmptyRecordsForWriteTools() {
        // Write tools never surface as silent-empty: their proposal snapshots may legitimately
        // contain an empty list field.
        Outcome outcome = ToolResultAnalyzer.classify(
                "create_reimbursement", "{\"ok\":true,\"records\":[]}", null);
        assertThat(outcome.failed()).isFalse();
    }

    @Test
    void classifyTreatsThrownExceptionAsHardFailure() {
        RuntimeException thrown = new IllegalStateException("db down");
        Outcome outcome = ToolResultAnalyzer.classify("query_project_budget", null, thrown);
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.kind()).isEqualTo(FailureKind.HARD_FAIL);
        assertThat(outcome.message()).contains("db down");
    }

    @Test
    void classifyReturnsNoneForSuccessfulNonEmptyResult() {
        Outcome outcome = ToolResultAnalyzer.classify(
                "query_project_budget", "{\"ok\":true,\"balance\":1000}", null);
        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.kind()).isEqualTo(FailureKind.NONE);
    }

    @Test
    void classifyTreatsBlankResultAsHardFailure() {
        Outcome outcome = ToolResultAnalyzer.classify("query_project_budget", "", null);
        assertThat(outcome.failed()).isTrue();
    }

    @Test
    void suggestMustCallReturnsAlternateToolsForKnownFailures() {
        assertThat(ToolResultAnalyzer.suggestMustCall("query_project_budget"))
                .containsExactly("list_projects");
        assertThat(ToolResultAnalyzer.suggestMustCall("get_reimbursement"))
                .containsExactly("list_reimbursements");
        assertThat(ToolResultAnalyzer.suggestMustCall("get_receipt_ocr")).isEmpty();
        assertThat(ToolResultAnalyzer.suggestMustCall("unknown_tool")).isEmpty();
    }

    @Test
    void isMetaToolCoversCalculatorsAndEscapeHatchReads() {
        Set<String> meta = ToolResultAnalyzer.METATOOL_ALLOW_LIST;
        assertThat(meta).contains(
                "get_current_datetime", "parse_date", "date_range", "sum_amounts",
                "budget_remaining", "list_projects", "recall_history",
                "register_attachment_receipt");
        assertThat(ToolResultAnalyzer.isMetaTool("list_projects")).isTrue();
        assertThat(ToolResultAnalyzer.isMetaTool("submit_reimbursement")).isFalse();
        assertThat(ToolResultAnalyzer.isMetaTool(null)).isFalse();
    }
}
