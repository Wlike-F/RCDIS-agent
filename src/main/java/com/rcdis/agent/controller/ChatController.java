package com.rcdis.agent.controller;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.dto.ChatRequest;
import com.rcdis.agent.dto.ChatResponse;
import com.rcdis.agent.service.AgentApplicationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Tag(name = "Agent Chat")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final AgentApplicationService agentApplicationService;

    @Qualifier("sseTaskExecutor")
    private final Executor sseTaskExecutor;

    @Operation(summary = "Send one chat message")
    @PostMapping
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.success(agentApplicationService.chat(request));
    }

    @Operation(summary = "Send one chat message with SSE streaming")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);
        sseTaskExecutor.execute(() -> emitChatStream(request, emitter));
        return emitter;
    }

    private void emitChatStream(ChatRequest request, SseEmitter emitter) {
        try {
            send(emitter, "start", Map.of("ok", true));
            ChatResponse response = agentApplicationService.chat(request);
            for (String token : splitForPrototypeStreaming(response.content())) {
                send(emitter, "token", Map.of("text", token));
            }
            send(emitter, "done", response);
            emitter.complete();
        } catch (Exception exception) {
            log.atError()
                    .setCause(exception)
                    .log("Chat stream failed");
            completeWithError(emitter, exception);
        }
    }

    private List<String> splitForPrototypeStreaming(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        return List.of(content);
    }

    private void send(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }

    private void completeWithError(SseEmitter emitter, Exception exception) {
        try {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", "CHAT_STREAM_FAILED");
            error.put("message", exception.getMessage());
            send(emitter, "error", error);
            emitter.complete();
        } catch (Exception sendException) {
            emitter.completeWithError(sendException);
        }
    }
}

