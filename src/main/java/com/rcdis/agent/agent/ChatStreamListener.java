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

    /**
     * Called for every reasoning-content delta the model streams ahead of the final answer.
     * Only fires on providers whose chat model exposes a native thinking channel (DeepSeek-R1,
     * Qwen3-thinking, Claude extended thinking, ...); silent on plain chat models so the
     * frontend can drive its UI purely by "thinking arrived vs. not" without a per-provider
     * flag. Default no-op keeps non-streaming transports unchanged.
     */
    default void onThinking(String token) {
    }

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

    /**
     * Called at most once per halt, after the streaming pipeline detects the model is spinning on
     * a failing tool. The payload describes which fields the assistant needs from the user
     * (e.g. project code, reimbursement number) plus a candidate shortlist so the frontend can
     * render a structured "pick or type a value" card instead of relying on the model asking.
     *
     * <p>Wire contract matches {@code agent_needs_input} SSE events; see
     * {@code frontend/src/api/chat.ts}.</p>
     */
    default void onNeedsInput(Map<String, Object> payload) {
    }
}
