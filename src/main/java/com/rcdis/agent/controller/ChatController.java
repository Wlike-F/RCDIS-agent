package com.rcdis.agent.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.rcdis.agent.agent.ChatStreamListener;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.dto.ChatConfirmRequest;
import com.rcdis.agent.dto.ChatConfirmResponse;
import com.rcdis.agent.dto.ChatRequest;
import com.rcdis.agent.dto.ChatResponse;
import com.rcdis.agent.service.AgentApplicationService;
import com.rcdis.agent.service.AgentPendingActionService;
import com.rcdis.agent.service.AgentTraceService;
import com.rcdis.agent.service.ChatHistoryService;
import com.rcdis.agent.vo.AgentMemoryVO;
import com.rcdis.agent.vo.AgentTurnTraceVO;
import com.rcdis.agent.vo.ChatMessageVO;
import com.rcdis.agent.vo.ChatSessionVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Chat transport layer.
 *
 * <p>Both endpoints share the same pipeline in {@link AgentApplicationService}; the only difference
 * is how tokens reach the client. The SSE endpoint emits events that match the frontend contract
 * in {@code frontend/src/api/chat.ts}: {@code start}, {@code token}, {@code tool_start},
 * {@code tool_result}, {@code requires_confirmation}, {@code error}, {@code done}. Write operations
 * are never executed inline: a write tool emits {@code requires_confirmation} and the mutation only
 * happens when the user posts to {@code /api/chat/confirm}.</p>
 */
@Slf4j
@Tag(name = "Agent Chat")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    /** Extra head-room on the emitter so the timeout fires after the service, not before it. */
    private static final long EMITTER_TIMEOUT_PADDING_MILLIS = 10_000L;

    private final AgentApplicationService agentApplicationService;
    private final AgentPendingActionService agentPendingActionService;
    private final AgentTraceService agentTraceService;
    private final ChatHistoryService chatHistoryService;
    private final AgentProperties agentProperties;

    @Qualifier("sseTaskExecutor")
    private final Executor sseTaskExecutor;

    @Operation(summary = "Sidebar sessions owned by the current user, most recently active first")
    @GetMapping("/sessions")
    public ApiResponse<List<ChatSessionVO>> sessions() {
        return ApiResponse.success(
                chatHistoryService.listSessions(CurrentUserContextHolder.currentOrAnonymous()));
    }

    @Operation(summary = "Renderable history turns of one owned conversation, oldest first")
    @GetMapping("/sessions/{conversationId}/messages")
    public ApiResponse<List<ChatMessageVO>> sessionMessages(@PathVariable String conversationId) {
        return ApiResponse.success(chatHistoryService.listMessages(
                conversationId, CurrentUserContextHolder.currentOrAnonymous()));
    }

    @Operation(summary = "Soft-delete an owned conversation (its history leaves the sidebar)")
    @DeleteMapping("/sessions/{conversationId}")
    public ApiResponse<Void> deleteSession(@PathVariable String conversationId) {
        chatHistoryService.deleteSession(
                conversationId, CurrentUserContextHolder.currentOrAnonymous());
        return ApiResponse.success(null);
    }

    @Operation(summary = "Send one chat message")
    @PostMapping
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.success(agentApplicationService.chat(request));
    }

    @Operation(summary = "Send one chat message with SSE streaming")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        long timeoutMillis = agentProperties.getStreamTimeoutSeconds() * 1000L + EMITTER_TIMEOUT_PADDING_MILLIS;
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        sseTaskExecutor.execute(() -> emitChatStream(request, emitter));
        return emitter;
    }

    @Operation(summary = "List per-turn observability traces of one conversation")
    @GetMapping("/traces")
    public ApiResponse<List<AgentTurnTraceVO>> traces(@RequestParam("conversationId") String conversationId) {
        chatHistoryService.requireOwnedSession(conversationId);
        return ApiResponse.success(agentTraceService.listByConversation(conversationId));
    }

    @Operation(summary = "Get conversation compression/memory state (rolling summary + hard facts)")
    @GetMapping("/memory")
    public ApiResponse<AgentMemoryVO> memory(@RequestParam("conversationId") String conversationId) {
        chatHistoryService.requireOwnedSession(conversationId);
        return ApiResponse.success(chatHistoryService.getMemorySnapshot(conversationId));
    }

    @Operation(summary = "Approve or reject a pending high-risk Agent action")
    @PostMapping("/confirm")
    public ApiResponse<ChatConfirmResponse> confirm(@Valid @RequestBody ChatConfirmRequest request) {
        return ApiResponse.success(agentPendingActionService.resolveConfirmation(
                request.conversationId(), request.confirmationId(), Boolean.TRUE.equals(request.approved())));
    }

    private void emitChatStream(ChatRequest request, SseEmitter emitter) {
        try {
            send(emitter, "start", Map.of("ok", true));
        } catch (IOException exception) {
            // Client already gone before we started; nothing else to do.
            emitter.completeWithError(exception);
            return;
        }
        try {
            agentApplicationService.streamChat(request, new SseChatStreamListener(emitter));
        } catch (RuntimeException exception) {
            log.atError()
                    .setCause(exception)
                    .addKeyValue("conversationId", request.conversationId())
                    .log("Chat stream failed");
            completeWithError(emitter, exception);
        }
    }

    private void send(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }

    private void completeWithError(SseEmitter emitter, Throwable exception) {
        try {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", errorCode(exception));
            error.put("message", exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage());
            send(emitter, "error", error);
            emitter.complete();
        } catch (Exception sendException) {
            emitter.completeWithError(sendException);
        }
    }

    private String errorCode(Throwable exception) {
        if (exception instanceof com.rcdis.agent.common.exception.BusinessException businessException) {
            return businessException.getCode();
        }
        return "CHAT_STREAM_FAILED";
    }

    /**
     * Bridges Agent service callbacks onto the SSE wire. Send failures are wrapped so Reactor sees
     * them as stream errors and disposes the upstream subscription; that is how a client disconnect
     * stops token generation instead of letting it run to completion in the background.
     */
    private final class SseChatStreamListener implements ChatStreamListener {

        private final SseEmitter emitter;

        private SseChatStreamListener(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void onToken(String token) {
            try {
                send(emitter, "token", Map.of("text", token));
            } catch (IOException exception) {
                throw new UncheckedIOException("SSE token send failed", exception);
            }
        }

        @Override
        public void onToolStart(Map<String, Object> payload) {
            try {
                send(emitter, "tool_start", payload);
            } catch (IOException exception) {
                throw new UncheckedIOException("SSE tool_start send failed", exception);
            }
        }

        @Override
        public void onToolResult(Map<String, Object> payload) {
            try {
                send(emitter, "tool_result", payload);
            } catch (IOException exception) {
                throw new UncheckedIOException("SSE tool_result send failed", exception);
            }
        }

        @Override
        public void onConfirmation(Map<String, Object> payload) {
            try {
                send(emitter, "requires_confirmation", payload);
            } catch (IOException exception) {
                throw new UncheckedIOException("SSE requires_confirmation send failed", exception);
            }
        }

        @Override
        public void onComplete(ChatResponse response) {
            try {
                send(emitter, "done", response);
                emitter.complete();
            } catch (IOException exception) {
                emitter.completeWithError(exception);
            }
        }

        @Override
        public void onError(Throwable error) {
            log.atWarn()
                    .setCause(error)
                    .log("Agent stream reported an error");
            completeWithError(emitter, error);
        }
    }
}
