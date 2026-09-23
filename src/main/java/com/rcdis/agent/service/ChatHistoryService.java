package com.rcdis.agent.service;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.entity.ChatMessageEntity;
import com.rcdis.agent.entity.ChatSessionEntity;
import com.rcdis.agent.to.ContextSnapshotTO;
import com.rcdis.agent.vo.AgentMemoryVO;
import com.rcdis.agent.vo.ChatMessageVO;
import com.rcdis.agent.vo.ChatSessionVO;

/**
 * Persistence boundary for Agent chat memory.
 *
 * <p>Sessions and messages live in PostgreSQL so that a browser refresh, a second device, or a
 * service restart all see the same conversation history. The model is never the source of truth
 * for what has been said; only this service decides what history the next turn is built from.</p>
 */
public interface ChatHistoryService {

    /**
     * Resolves the session for a conversation, creating it on first use and rebinding the provider
     * code when the caller supplies a different one. Enforces that the current user owns the
     * session; a mismatch is rejected instead of silently adopting the requester.
     */
    ChatSessionEntity resolveSession(String conversationId, String requestedProviderCode);

    /** Requires that the current authenticated user owns an existing conversation. */
    ChatSessionEntity requireOwnedSession(String conversationId);

    /**
     * Loads the recent raw window (after the compressed boundary) for model injection.
     * Kept for compatibility; prefer {@link #loadContextForModel(String)} which also returns the
     * compressed prefix.
     */
    List<Message> loadHistoryForModel(String conversationId, int maxMessages);

    /**
     * Builds what the model should see this turn: compressed prefix (rolling summary + facts)
     * plus the most recent raw turns within the message-count and token budgets.
     */
    ContextSnapshotTO loadContextForModel(String conversationId);

    /** Compression state for the trace-tab memory panel. */
    AgentMemoryVO getMemorySnapshot(String conversationId);

    /** Raw turns in a seq range, for recall_history and the compressor. */
    List<ChatMessageEntity> readTurns(String conversationId, int fromSeq, int toSeq);

    /** System-internal session lookup (no ownership check); used by the context compressor. */
    ChatSessionEntity peekSession(String conversationId);

    /** Persists in-memory session mutations (used by the compressor to save compression state). */
    void updateSession(ChatSessionEntity session);

    /** Appends a user turn and updates the session counters. */
    void appendUserMessage(ChatSessionEntity session, String content);

    /**
     * Appends a tool-result row (role={@code tool}) so tool conclusions enter the compressible
     * history. Tool rows are NOT fed back into the model window (message ordering), but they are
     * visible to the compressor (facts source) and to recall_history.
     */
    void appendToolMessage(ChatSessionEntity session, String content);

    /**
     * Appends an assistant turn after streaming finishes. {@code status} is {@code DONE} on
     * success or {@code ERROR} when the model call failed; the partial content collected so far is
     * still saved so the UI can show what was received.
     */
    void appendAssistantMessage(ChatSessionEntity session,
                                String content,
                                String providerCode,
                                String modelName,
                                String status,
                                String errorMessage,
                                Integer tokenCount);

    /** Fills in the session title from the first user message when it is still blank. */
    void updateSessionTitleIfBlank(ChatSessionEntity session, String firstUserMessage);

    /**
     * Sidebar sessions owned by the given user, most recently active first. Powers the session
     * list endpoint so the UI no longer depends on browser localStorage for the list.
     */
    List<ChatSessionVO> listSessions(CurrentUserTO user);

    /**
     * Renderable history turns (user / assistant only, tool rows are internal) of one owned
     * conversation, oldest first. Throws when the session does not exist or belongs to another user.
     */
    List<ChatMessageVO> listMessages(String conversationId, CurrentUserTO user);

    /** Soft-deletes an owned session; its lines stop appearing in the sidebar and context loading. */
    void deleteSession(String conversationId, CurrentUserTO user);
}
