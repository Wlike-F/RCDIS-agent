package com.rcdis.agent.service;

import com.rcdis.agent.agent.ChatStreamListener;
import com.rcdis.agent.dto.ChatRequest;
import com.rcdis.agent.dto.ChatResponse;

/**
 * Application-level entry point for Agent chat.
 *
 * <p>Two transports share the same pipeline: {@link #chat(ChatRequest)} blocks and returns the
 * final assistant turn, while {@link #streamChat(ChatRequest, ChatStreamListener)} pushes tokens
 * incrementally. Both persist user and assistant turns to PostgreSQL via
 * {@link ChatHistoryService}; the model is never the source of truth for conversation history.</p>
 */
public interface AgentApplicationService {

    /** Blocking single-turn chat, kept for the plain {@code POST /api/chat} endpoint and for tests. */
    ChatResponse chat(ChatRequest request);

    /**
     * Streams one turn to the given listener. The call blocks the invoking thread until the model
     * finishes, the stream times out, or an error surfaces; the caller is expected to run it on a
     * worker pool (see {@code sseTaskExecutor}).
     */
    void streamChat(ChatRequest request, ChatStreamListener listener);
}
