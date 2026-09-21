package com.rcdis.agent.agent;

import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;

import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.entity.ChatSessionEntity;

/**
 * Per-request context handed to Agent tools through Spring AI's {@link ToolContext}.
 *
 * <p>Tool beans are singletons, but each request carries its own SSE listener, conversation id and
 * verified identity. Passing them through {@link ToolContext} (instead of a {@code ThreadLocal})
 * keeps tools correct even when Spring AI invokes them on a Reactor or executor thread where the
 * request-scoped {@code CurrentUserContextHolder} would be empty.</p>
 */
public record AgentToolContext(
        ChatStreamListener listener,
        String conversationId,
        CurrentUserTO currentUser,
        TurnTraceCollector trace,
        ChatSessionEntity session
) {

    /** Key under which this context is stored in the {@link ToolContext} map. */
    public static final String CONTEXT_KEY = "agentToolContext";

    public static Map<String, Object> asToolContextMap(AgentToolContext context) {
        return Map.of(CONTEXT_KEY, context);
    }

    /**
     * Extracts the context from a {@link ToolContext}; returns {@code null} when absent so a tool
     * can degrade gracefully (skip SSE reporting) rather than crash the model call.
     */
    public static AgentToolContext from(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(CONTEXT_KEY);
        return value instanceof AgentToolContext context ? context : null;
    }
}
