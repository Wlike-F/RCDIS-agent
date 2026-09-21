package com.rcdis.agent.agent;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import com.rcdis.agent.entity.ChatSessionEntity;
import com.rcdis.agent.service.ChatHistoryService;
import com.rcdis.agent.service.AgentToolAuthorizationService;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.exception.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.rcdis.agent.config.AgentProperties;

/**
 * Decorator that persists every tool result as a {@code role=tool} chat_message row.
 *
 * <p>Centralised so no individual {@code @Tool} method needs to remember to record itself: the
 * decorator wraps each callback produced from the tool beans and writes the (truncated) result
 * after the delegate returns. Tool rows are excluded from the model window (message ordering) but
 * feed the compressor's facts source and recall_history, closing the "tool conclusions vanish
 * across turns" gap.</p>
 *
 * <p>Recording is best-effort: a persistence failure must never abort the model call.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class RecordingToolCallback implements ToolCallback {

    private static final int RESULT_MAX_CHARS = 2000;

    private final ToolCallback delegate;
    private final ChatHistoryService chatHistoryService;
    private final AgentToolAuthorizationService agentToolAuthorizationService;
    private final AgentMetrics agentMetrics;

    /** Compatibility constructor for isolated callback tests that do not build the Spring context. */
    public RecordingToolCallback(
            ToolCallback delegate,
            ChatHistoryService chatHistoryService,
            AgentToolAuthorizationService agentToolAuthorizationService) {
        this(delegate, chatHistoryService, agentToolAuthorizationService,
                new AgentMetrics(new SimpleMeterRegistry(), new AgentProperties()));
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
            String result = delegate.call(toolInput, toolContext);
            record(toolInput, toolContext, result);
            success = true;
            return result;
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
}
