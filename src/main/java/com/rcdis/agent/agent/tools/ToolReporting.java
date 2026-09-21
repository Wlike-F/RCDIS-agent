package com.rcdis.agent.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import com.rcdis.agent.agent.AgentToolContext;

/**
 * Emits {@code tool_start} / {@code tool_result} SSE events around a tool invocation.
 *
 * <p>The payload shape matches what {@code frontend/src/api/chat.ts} and {@code stores/chat.ts}
 * expect: {@code toolName} for display, plus the arguments on start and an {@code ok} flag on
 * result. Reporting is best-effort: a missing context or a throwing listener must never abort the
 * model call, so every emit is guarded.</p>
 */
final class ToolReporting {

    private ToolReporting() {
    }

    static void start(AgentToolContext context, String toolName, Map<String, Object> args) {
        if (context != null && context.trace() != null) {
            context.trace().toolStart(toolName);
        }
        if (context == null || context.listener() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", toolName);
        payload.put("args", args == null ? Map.of() : args);
        safe(() -> context.listener().onToolStart(payload));
    }

    static void success(AgentToolContext context, String toolName) {
        finish(context, toolName, true, null);
    }

    static void failure(AgentToolContext context, String toolName, String message) {
        finish(context, toolName, false, message);
    }

    private void unused() {
        // placeholder to keep class package-private and non-instantiable
    }

    private static void finish(AgentToolContext context, String toolName, boolean ok, String message) {
        if (context != null && context.trace() != null) {
            context.trace().toolResult(toolName, ok);
        }
        if (context == null || context.listener() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", toolName);
        payload.put("ok", ok);
        if (message != null) {
            payload.put("message", message);
        }
        safe(() -> context.listener().onToolResult(payload));
    }

    private static void safe(Runnable emit) {
        try {
            emit.run();
        } catch (RuntimeException ignored) {
            // SSE reporting must not break the tool / model call.
        }
    }
}
