package com.rcdis.agent.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.agent.TurnTraceCollector.HaltTier;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.entity.ChatSessionEntity;
import com.rcdis.agent.service.AgentToolAuthorizationService;
import com.rcdis.agent.service.ChatHistoryService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Decorator around every registered {@code @Tool} callback. Two responsibilities:
 *
 * <ol>
 *   <li><b>Persistence</b>: records each tool result as a {@code role=tool} chat_message row so
 *       the compressor's facts source and {@code recall_history} see the raw output rather than
 *       only the assistant's paraphrase. Failures here are logged and swallowed; they must never
 *       abort the model call.</li>
 *   <li><b>Runtime self-healing</b>: watches for the model spinning on a failing tool and
 *       escalates through three tiers — soft hint (append {@code haltHint} to the result),
 *       hard halt (replace result with a {@code halt:true} block listing {@code must_call}
 *       alternatives and queue an {@code agent_needs_input} payload), freeze (permanently
 *       short-circuit the offending tool for the rest of the turn and return a "frozen" JSON).
 *       All state lives on the per-turn {@link TurnTraceCollector} so freezing naturally
 *       expires at turn boundary.</li>
 * </ol>
 *
 * <p>The circuit breaker honours {@link AgentProperties.Guard#isEnabled()}; flipping that switch
 * off restores the pre-halt behaviour verbatim (delegate result passed through unchanged), which
 * is the escape hatch for a false-positive regression.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class RecordingToolCallback implements ToolCallback {

    private static final int RESULT_MAX_CHARS = 2000;
    /** Stable code for the JSON contract between the callback, the SSE wire and the frontend card. */
    static final String NEEDS_INPUT_EVENT = "agent_needs_input";

    private final ToolCallback delegate;
    private final ChatHistoryService chatHistoryService;
    private final AgentToolAuthorizationService agentToolAuthorizationService;
    private final AgentMetrics agentMetrics;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;

    /** Compatibility constructor for isolated callback tests that do not build the Spring context. */
    public RecordingToolCallback(
            ToolCallback delegate,
            ChatHistoryService chatHistoryService,
            AgentToolAuthorizationService agentToolAuthorizationService) {
        this(delegate, chatHistoryService, agentToolAuthorizationService,
                new AgentMetrics(new SimpleMeterRegistry(), new AgentProperties()),
                new AgentProperties(),
                new ObjectMapper());
    }

    /**
     * Compatibility constructor retaining the previous 4-argument shape used by metric tests.
     * Guard defaults from {@link AgentProperties} are applied, and a fresh {@link ObjectMapper}
     * is allocated for the halt-payload serialisation path.
     */
    public RecordingToolCallback(
            ToolCallback delegate,
            ChatHistoryService chatHistoryService,
            AgentToolAuthorizationService agentToolAuthorizationService,
            AgentMetrics agentMetrics) {
        this(delegate, chatHistoryService, agentToolAuthorizationService, agentMetrics,
                new AgentProperties(), new ObjectMapper());
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        throw new BusinessException(
                "AGENT_TOOL_CONTEXT_REQUIRED",
                "Agent 工具必须在经过身份验证的 ToolContext 中执行");
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        AgentToolContext context = AgentToolContext.from(toolContext);
        CurrentUserTO previous = CurrentUserContextHolder.currentOrNull();
        long startedAt = System.nanoTime();
        String toolName = delegate.getToolDefinition().name();
        boolean success = false;
        try {
            if (context != null && context.currentUser() != null) {
                CurrentUserContextHolder.set(context.currentUser());
            }
            agentToolAuthorizationService.authorize(toolName, toolInput, context);

            String guarded = preflightOrThrow(toolName, context);
            if (guarded != null) {
                // Frozen by an earlier halt in the same turn: short-circuit, do not delegate.
                success = true;
                record(toolInput, toolContext, guarded);
                return guarded;
            }

            InvocationResult invocation = invokeDelegate(toolInput, toolContext);
            String finalResult = applyHaltPipeline(toolName, toolInput, invocation, context);
            record(toolInput, toolContext, finalResult);
            success = invocation.failure() == null;
            return finalResult;
        } finally {
            agentMetrics.recordTool(toolName,
                    success,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            if (previous == null) {
                CurrentUserContextHolder.clear();
            } else {
                CurrentUserContextHolder.set(previous);
            }
        }
    }

    // ---------- circuit breaker ----------

    /**
     * Returns a synthetic "frozen" JSON when the tool was frozen earlier this turn; {@code null}
     * means proceed with the delegate call. Meta tools never enter the frozen set (enforced in
     * {@link #freezeIfPossible}), so they always return {@code null}.
     */
    private String preflightOrThrow(String toolName, AgentToolContext context) {
        TurnTraceCollector collector = context == null ? null : context.trace();
        if (collector == null || !agentProperties.getGuard().isEnabled()) {
            return null;
        }
        if (!collector.isFrozen(toolName)) {
            return null;
        }
        agentMetrics.recordFreezeHit(toolName);
        log.atInfo()
                .addKeyValue("tool", toolName)
                .addKeyValue("conversationId", context.conversationId())
                .log("Agent tool frozen this turn; short-circuited without invoking");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("frozen", true);
        payload.put("halt", true);
        payload.put("reason", "该工具本轮已被冻结（连续失败或超出调用上限），请勿再次调用");
        payload.put("next", List.of("ask_user"));
        return writeJson(payload, "工具冻结响应序列化失败");
    }

    private InvocationResult invokeDelegate(String toolInput, ToolContext toolContext) {
        try {
            String result = delegate.call(toolInput, toolContext);
            return new InvocationResult(result, null);
        } catch (RuntimeException exception) {
            // Rethrown as a classified failure below; do not propagate to Spring AI so it can
            // retry the tool. The whole point of the halt pipeline is to convert exceptions into
            // structured model-visible signals.
            return new InvocationResult(null, exception);
        }
    }

    private String applyHaltPipeline(
            String toolName, String toolInput, InvocationResult invocation, AgentToolContext context) {
        TurnTraceCollector collector = context == null ? null : context.trace();
        AgentProperties.Guard guard = agentProperties.getGuard();
        if (collector == null || !guard.isEnabled()) {
            // Legacy behaviour: propagate the delegate exception unchanged so the outer pipeline
            // records it as a stream error.
            if (invocation.failure() != null) {
                throw invocation.failure();
            }
            return invocation.result();
        }
        ToolResultAnalyzer.Outcome outcome =
                ToolResultAnalyzer.classify(toolName, invocation.result(), invocation.failure());
        String inputHash = ToolResultAnalyzer.hashInput(toolInput);
        HaltTier tier = collector.registerOutcome(
                toolName, inputHash, outcome.failed(),
                guard.getHardHaltThreshold(), guard.getFreezeThreshold());
        boolean ceilingBreached = collector.isTurnCeilingReached(guard.getTurnToolCallCeiling());

        // A successful call after the ceiling is still allowed to pass through: the ceiling only
        // blocks *further* failures, not a working model that happens to be verbose.
        String baseResult = resolveBaseResult(toolName, invocation, outcome);
        switch (tier) {
            case OK:
                return baseResult;
            case SOFT_HINT:
                collector.setHaltReason("SOFT_HINT");
                agentMetrics.recordHalt("soft_hint", toolName);
                return appendSoftHint(baseResult, outcome);
            case HARD_HALT:
                collector.setHaltReason("HARD_HALT");
                agentMetrics.recordHalt("hard_halt", toolName);
                freezeIfPossible(collector, toolName, guard);
                queueNeedsInput(context, collector, toolName, outcome, "HARD_HALT");
                return buildHardHaltJson(toolName, outcome);
            case FREEZE:
            default:
                collector.setHaltReason(ceilingBreached ? "CEILING" : "FREEZE");
                agentMetrics.recordHalt(ceilingBreached ? "ceiling" : "freeze", toolName);
                // Meta tools (list_projects, get_current_datetime, ...) must never be frozen:
                // they are the escape hatch the HARD_HALT tier suggests, so freezing one would
                // trap the model with no way forward. Fall back to a HARD_HALT-shaped result
                // for them instead.
                if (!ToolResultAnalyzer.isMetaTool(toolName)) {
                    collector.freeze(toolName);
                }
                queueNeedsInput(context, collector, toolName, outcome, "FREEZE");
                return ToolResultAnalyzer.isMetaTool(toolName)
                        ? buildHardHaltJson(toolName, outcome)
                        : buildFrozenJson(toolName, outcome);
        }
    }

    private String resolveBaseResult(String toolName, InvocationResult invocation,
                                     ToolResultAnalyzer.Outcome outcome) {
        if (invocation.result() != null) {
            return invocation.result();
        }
        // Exception path: convert to a structured ok:false so the model has something parseable.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("tool", toolName);
        payload.put("error", outcome.message() == null ? "工具执行失败" : outcome.message());
        return writeJson(payload, "工具异常回退序列化失败");
    }

    /**
     * Appends {@code haltHint} without disturbing the delegate result. The extra key is inserted
     * just before the closing brace of a JSON object; if the result is not a JSON object (rare,
     * e.g. a raw string from an external integration) it is returned unchanged and a warning is
     * logged. Preserving the original shape keeps downstream parsing (assistant text, eval
     * assertions) stable.
     */
    private String appendSoftHint(String result, ToolResultAnalyzer.Outcome outcome) {
        if (result == null) {
            return null;
        }
        String trimmed = result.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            log.atDebug().log("haltHint skipped: delegate result is not a JSON object");
            return result;
        }
        String hint = outcome.kind() == ToolResultAnalyzer.FailureKind.SILENT_EMPTY
                ? "工具返回空集合，请检查参数或改用 list_* 系列工具获取候选项"
                : "工具返回失败，先向用户核对参数或改用替代工具，不要盲目重试同一参数";
        return trimmed.substring(0, trimmed.length() - 1) + ",\"haltHint\":\"" + escape(hint) + "\"}";
    }

    private String buildHardHaltJson(String toolName, ToolResultAnalyzer.Outcome outcome) {
        Set<String> mustCall = ToolResultAnalyzer.suggestMustCall(toolName);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("halt", true);
        payload.put("tier", "HARD_HALT");
        payload.put("tool", toolName);
        payload.put("reason", outcome.message() == null ? "工具连续失败" : outcome.message());
        payload.put("must_call", List.copyOf(mustCall));
        payload.put("next", mustCall.isEmpty() ? List.of("ask_user") : List.of("call_then_ask_user"));
        payload.put("message", "系统已阻断同一工具的重复调用。若 must_call 非空请先调用其中列出的工具；"
                + "无论如何都需向用户复述当前障碍并请其补充关键参数。");
        return writeJson(payload, "HARD_HALT 序列化失败");
    }

    private String buildFrozenJson(String toolName, ToolResultAnalyzer.Outcome outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("halt", true);
        payload.put("frozen", true);
        payload.put("tier", "FREEZE");
        payload.put("tool", toolName);
        payload.put("reason", outcome.message() == null ? "工具已达冻结阈值" : outcome.message());
        payload.put("must_call", List.copyOf(ToolResultAnalyzer.suggestMustCall(toolName)));
        payload.put("next", List.of("ask_user"));
        payload.put("message", "该工具在本轮对话内已被冻结，无法再调用。请直接向用户说明障碍并请求补充信息。");
        return writeJson(payload, "FREEZE 序列化失败");
    }

    private void freezeIfPossible(TurnTraceCollector collector, String toolName,
                                  AgentProperties.Guard guard) {
        // HARD_HALT alone does not freeze; only the FREEZE tier (or the turn ceiling) blocks
        // subsequent calls, so a model that immediately pivots to a different tool still works.
        if (collector.totalToolCalls() > guard.getTurnToolCallCeiling()
                && guard.getTurnToolCallCeiling() > 0) {
            collector.freeze(toolName);
        }
    }

    private void queueNeedsInput(AgentToolContext context, TurnTraceCollector collector,
                                 String toolName, ToolResultAnalyzer.Outcome outcome, String tier) {
        // Queue on the collector regardless of listener presence: the pipeline drains this list
        // at turn end and only needs a listener to actually push over SSE. Blocking queueing on
        // a missing listener would silently drop the halt data for tests and non-streaming paths.
        if (collector == null) {
            return;
        }
        String conversationId = context == null ? null : context.conversationId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tier", tier);
        payload.put("sourceTool", toolName);
        payload.put("reason", outcome.message() == null ? "工具执行受阻" : outcome.message());
        payload.put("missingFields", suggestFieldsFor(toolName));
        payload.put("mustCall", List.copyOf(ToolResultAnalyzer.suggestMustCall(toolName)));
        payload.put("conversationId", conversationId);
        collector.queueNeedsInput(payload);
    }

    /**
     * Fields the model is most likely missing for each tool, used by the frontend card to render
     * a targeted input prompt instead of a generic "please retry" message.
     */
    private static List<String> suggestFieldsFor(String toolName) {
        if (toolName == null) {
            return List.of();
        }
        return switch (toolName) {
            case "query_project_budget", "summarize_expenses", "list_expense_items" -> List.of("projectCode");
            case "get_reimbursement", "check_reimbursement_materials",
                    "generate_reimbursement_summary", "submit_reimbursement",
                    "update_reimbursement", "void_reimbursement" -> List.of("reimbursementNo");
            case "get_receipt_ocr" -> List.of("receiptId");
            default -> List.of();
        };
    }

    // ---------- persistence ----------

    private void record(String toolInput, ToolContext toolContext, String result) {
        try {
            AgentToolContext context = AgentToolContext.from(toolContext);
            if (context == null || context.session() == null) {
                return;
            }
            ChatSessionEntity session = context.session();
            String name = delegate.getToolDefinition().name();
            String content = name + " -> " + truncate(result);
            chatHistoryService.appendToolMessage(session, content);
        } catch (RuntimeException exception) {
            log.atWarn()
                    .setCause(exception)
                    .log("Failed to persist tool result row; continuing without it");
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String single = value.replaceAll("\\s+", " ").trim();
        return single.length() <= RESULT_MAX_CHARS ? single : single.substring(0, RESULT_MAX_CHARS);
    }

    private String writeJson(Map<String, Object> payload, String failureLabel) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            log.atWarn().setCause(exception).log(failureLabel);
            return "{\"ok\":false,\"error\":\"序列化" + failureLabel + "\"}";
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Result of one delegate call, split so the halt pipeline can inspect failure without a try. */
    private record InvocationResult(String result, RuntimeException failure) {
    }
}
