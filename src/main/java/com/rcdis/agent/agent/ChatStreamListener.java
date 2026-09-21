package com.rcdis.agent.agent;

import java.util.Map;

import com.rcdis.agent.dto.ChatResponse;

/**
 * Callbacks the Agent service uses to push progress to a transport (SSE, sync collector, tests).
 *
 * <p>The controller layer owns the wire format; the service only reports semantic events. Keeping
 * the two apart means the same streaming pipeline can serve both {@code POST /api/chat} and
 * {@code POST /api/chat/stream} without branching inside the service.</p>
 */
public interface ChatStreamListener {

    /** A no-op listener, useful for the blocking {@code chat(...)} entry point and for tests. */
    ChatStreamListener NOOP = new ChatStreamListener() {
        @Override
        public void onToken(String token) {
        }

        @Override
        public void onComplete(ChatResponse response) {
        }

        @Override
        public void onError(Throwable error) {
        }
    };

    /** Called for every token the model emits, in arrival order. */
    void onToken(String token);

    /** Called once when the stream finished successfully and the assistant turn has been persisted. */
    void onComplete(ChatResponse response);

    /** Called once when the stream failed; the partial turn is still persisted with status ERROR. */
    void onError(Throwable error);

    /**
     * Called when an Agent tool is about to run. Default no-op so transports that do not surface
     * tool activity (blocking chat, tests) need not implement it.
     */
    default void onToolStart(Map<String, Object> payload) {
    }

    /** Called when an Agent tool finished, carrying an {@code ok} flag and optional message. */
    default void onToolResult(Map<String, Object> payload) {
    }

    /**
     * Called when a write tool produced a pending proposal that needs explicit user confirmation
     * before executing. Payload keys match {@code frontend/src/stores/chat.ts toConfirmation()}.
     */
    default void onConfirmation(Map<String, Object> payload) {
    }
}
