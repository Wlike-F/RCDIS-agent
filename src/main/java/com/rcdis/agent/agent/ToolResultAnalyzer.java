package com.rcdis.agent.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Package-private pure helpers powering {@link RecordingToolCallback}'s three-tier circuit breaker.
 *
 * <p>Deliberately stateless: everything is a function of the tool name, its input JSON, and the
 * delegate's return string. Keeping it out of the callback keeps the callback small and lets the
 * classifier evolve independently (add a new "silent-empty" pattern here without touching the
 * Reactor / persistence plumbing).</p>
 */
final class ToolResultAnalyzer {

    /**
     * Tools that must never be frozen: pure calculators (date, sum) and the "escape hatch" reads
     * the breaker itself suggests ({@code list_projects}, {@code recall_history}). Freezing one of
     * these would trap the model in a state where it cannot make progress.
     */
    static final Set<String> METATOOL_ALLOW_LIST = Set.of(
            "get_current_datetime",
            "parse_date",
            "date_range",
            "sum_amounts",
            "budget_remaining",
            "list_projects",
            "recall_history",
            "register_attachment_receipt");

    /**
     * Suggested next tools keyed by the failing tool. When a tool hits HARD_HALT the callback
     * embeds these names so the model can pivot to a legitimate alternate path instead of
     * retrying the same query blindly.
     */
    private static final Map<String, Set<String>> MUST_CALL_ON_HALT = Map.ofEntries(
            Map.entry("query_project_budget", Set.of("list_projects")),
            Map.entry("summarize_expenses", Set.of("list_projects")),
            Map.entry("list_expense_items", Set.of("list_projects")),
            Map.entry("get_reimbursement", Set.of("list_reimbursements")),
            Map.entry("check_reimbursement_materials", Set.of("get_reimbursement", "list_reimbursements")),
            Map.entry("generate_reimbursement_summary", Set.of("get_reimbursement")),
            Map.entry("list_pending_approvals", Set.of("list_reimbursements")),
            Map.entry("get_receipt_ocr", Set.of()));

    /**
     * Read-only list tools whose successful call returning an empty array means "no data to
     * continue" — a silent-empty condition distinct from a hard failure.
     */
    private static final Set<String> SILENT_EMPTY_KEYS = Set.of(
            "records", "items", "data", "rows", "results");

    private ToolResultAnalyzer() {
        throw new UnsupportedOperationException("ToolResultAnalyzer cannot be instantiated");
    }

    /**
     * Normalized hash of the tool arguments used to detect "identical retry". Whitespace is
     * stripped, JSON keys are sorted via canonical re-emission when possible, and the result is
     * truncated to a compact 12-hex-character digest to keep collector maps small.
     */
    static String hashInput(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return "";
        }
        String normalized = toolInput
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is mandated by the JLS; if a JVM ever lacks it, degrade to a 32-bit hash.
            return Integer.toHexString(normalized.hashCode());
        }
    }

    /**
     * Classifies one delegate result. A tool call is treated as failed when either:
     * <ul>
     *   <li>the delegate threw a RuntimeException (signalled by the callback via {@code throwable});</li>
     *   <li>the JSON body contains {@code "ok":false}, {@code "success":false},
     *       {@code "status":"FAILED"/"ERROR"/"PENDING"};</li>
     *   <li>the JSON body reports a recognized list key as an empty array — silent-empty.</li>
     * </ul>
     * The classifier is text-substring based on purpose: parsing every tool result through Jackson
     * inside the hot callback path adds measurable latency; the shape patterns below are stable
     * because all @Tool methods already serialize through a single {@code json(...)} helper.
     */
    static Outcome classify(String toolName, String result, Throwable throwable) {
        if (throwable != null) {
            return new Outcome(true, FailureKind.HARD_FAIL, rootMessage(throwable));
        }
        if (result == null || result.isBlank()) {
            return new Outcome(true, FailureKind.HARD_FAIL, "工具返回为空");
        }
        String probe = result.replaceAll("\\s+", "");
        if (containsAny(probe, "\"ok\":false", "\"success\":false")) {
            return new Outcome(true, FailureKind.HARD_FAIL, firstErrorField(result));
        }
        if (containsAny(probe, "\"status\":\"FAILED\"", "\"status\":\"ERROR\"", "\"status\":\"PENDING\"")) {
            return new Outcome(true, FailureKind.HARD_FAIL, firstErrorField(result));
        }
        // Silent-empty only for read tools that return a collection. Write tools legitimately
        // return "records: []" as part of a proposal snapshot, so excluding WRITE names.
        if (isReadListTool(toolName) && reportsEmptyCollection(probe)) {
            return new Outcome(true, FailureKind.SILENT_EMPTY, "工具返回了空集合，说明目标不存在或没有可继续的数据");
        }
        return new Outcome(false, FailureKind.NONE, null);
    }

    /**
     * Returns the alternate-path suggestion for a HARD_HALT tier, or an empty set when the
     * "correct" next step is asking the user rather than calling another tool.
     */
    static Set<String> suggestMustCall(String toolName) {
        return MUST_CALL_ON_HALT.getOrDefault(toolName, Set.of());
    }

    static boolean isMetaTool(String toolName) {
        // Set.of returns an immutable set whose contains(null) throws NPE; guard explicitly so
        // callers can pass an unresolved tool name without a defensive check.
        return toolName != null && METATOOL_ALLOW_LIST.contains(toolName);
    }

    // ---------- helpers ----------

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Read-only tools that return a paginated list. Write tools never surface as "silent empty"
     * because their result payload shape is a proposal, not a query result.
     */
    private static boolean isReadListTool(String toolName) {
        if (toolName == null) {
            return false;
        }
        return toolName.startsWith("list_")
                || toolName.equals("summarize_expenses")
                || toolName.equals("generate_reimbursement_summary");
    }

    private static boolean reportsEmptyCollection(String compactJson) {
        for (String key : SILENT_EMPTY_KEYS) {
            if (compactJson.contains("\"" + key + "\":[]")) {
                return true;
            }
        }
        // A bare "[]" as the whole result body also counts (list-returning @Tool shortcut).
        return compactJson.equals("[]");
    }

    private static String firstErrorField(String result) {
        int index = result.indexOf("\"error\"");
        if (index < 0) {
            index = result.indexOf("\"message\"");
        }
        if (index < 0) {
            return abbreviate(result);
        }
        int colon = result.indexOf(':', index);
        if (colon < 0) {
            return abbreviate(result);
        }
        int quoteOpen = result.indexOf('"', colon + 1);
        if (quoteOpen < 0) {
            return abbreviate(result);
        }
        int quoteClose = result.indexOf('"', quoteOpen + 1);
        if (quoteClose < 0) {
            return abbreviate(result);
        }
        return result.substring(quoteOpen + 1, quoteClose);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        String message = current.getMessage();
        while ((message == null || message.isBlank()) && current.getCause() != null) {
            current = current.getCause();
            message = current.getMessage();
        }
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String singleLine = value.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 200 ? singleLine : singleLine.substring(0, 200);
    }

    /** Outcome of {@link #classify}: whether the callback should escalate and why. */
    record Outcome(boolean failed, FailureKind kind, String message) {
    }

    enum FailureKind {
        /** Successful call, or a successful empty result on a write tool. */
        NONE,
        /** Explicit failure signal (exception, {@code ok:false}, {@code status:FAILED/PENDING}). */
        HARD_FAIL,
        /** Read tool returned an empty collection — not a bug but no progress either. */
        SILENT_EMPTY
    }
}
