package com.rcdis.agent.service.impl;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.common.util.TokenEstimator;
import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.entity.ChatMessageEntity;
import com.rcdis.agent.entity.ChatSessionEntity;
import com.rcdis.agent.mapper.ChatMessageMapper;
import com.rcdis.agent.mapper.ChatSessionMapper;
import com.rcdis.agent.service.ChatHistoryService;
import com.rcdis.agent.to.ContextSnapshotTO;
import com.rcdis.agent.vo.AgentMemoryVO;
import com.rcdis.agent.vo.ChatMessageVO;
import com.rcdis.agent.vo.ChatSessionVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PostgreSQL-backed chat memory.
 *
 * <p>Rows are written under a per-conversation lock held by
 * {@link com.rcdis.agent.service.impl.AgentApplicationServiceImpl}, so {@code message_count} and
 * {@code last_message_at} stay monotonic without additional coordination. History loads only
 * {@code DONE} rows: an errored or partially streamed turn is not fed back to the model.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryServiceImpl implements ChatHistoryService {

    /**
     * Roles that may be replayed to the model as chat messages, and the only roles the raw prompt
     * window is built from. Internal {@code tool} rows are deliberately absent: Spring AI owns the
     * tool-call/tool-result pairing inside a single turn, and re-injecting stale tool rows as chat
     * messages would desynchronise that pairing.
     */
    private static final List<String> MODEL_WINDOW_ROLES = List.of("user", "assistant");

    /**
     * Roles the compressor and {@code recall_history} may read: the model window set plus the
     * internal {@code tool} rows, so a verified tool conclusion survives into {@code summary_facts}
     * and stays re-readable after its raw turn leaves the window.
     */
    private static final List<String> COMPRESSIBLE_ROLES = List.of("user", "assistant", "tool");
    private static final String STATUS_DONE = "DONE";
    private static final String STATUS_SESSION_ACTIVE = "ACTIVE";
    private static final int TITLE_MAX_LENGTH = 30;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;
    private static final int USER_CONTENT_MAX_LENGTH = 32_000;
    private static final int ASSISTANT_CONTENT_MAX_LENGTH = 32_000;
    private static final int TOOL_CONTENT_MAX_LENGTH = 8_000;
    private static final String RETAINED_CONTENT_MARKER = "[REDACTED_BY_RETENTION_POLICY]";

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ChatSessionEntity resolveSession(String conversationId, String requestedProviderCode) {
        if (!StringUtils.hasText(conversationId)) {
            throw new BusinessException("CHAT_CONVERSATION_ID_MISSING", "conversationId 不能为空");
        }
        CurrentUserTO currentUser = CurrentUserContextHolder.currentOrAnonymous();
        ChatSessionEntity existing = findSessionByConversationId(conversationId);
        if (existing == null) {
            return createSession(conversationId, requestedProviderCode, currentUser);
        }
        if (!existing.getUserId().equals(currentUser.userId())) {
            // Ownership mismatch: refuse instead of leaking another user's history into this thread.
            log.atWarn()
                    .addKeyValue("conversationId", conversationId)
                    .addKeyValue("ownerUserId", existing.getUserId())
                    .addKeyValue("requestUserId", currentUser.userId())
                    .log("Rejected chat session access from a different user");
            throw new BusinessException(
                    "CHAT_SESSION_FORBIDDEN",
                    "该对话属于其他用户，无法访问。conversationId=" + conversationId);
        }
        if (StringUtils.hasText(requestedProviderCode)
                && !requestedProviderCode.equals(existing.getProviderCode())) {
            existing.setProviderCode(requestedProviderCode.trim());
            chatSessionMapper.updateById(existing);
        }
        return existing;
    }

    @Override
    public ChatSessionEntity requireOwnedSession(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            throw new BusinessException("CHAT_CONVERSATION_ID_MISSING", "conversationId 不能为空");
        }
        ChatSessionEntity session = findSessionByConversationId(conversationId.trim());
        if (session == null) {
            throw new BusinessException("CHAT_SESSION_NOT_FOUND", "对话不存在。conversationId=" + conversationId);
        }
        CurrentUserTO currentUser = CurrentUserContextHolder.currentOrAnonymous();
        if (!currentUser.userId().equals(session.getUserId())) {
            throw new BusinessException("CHAT_SESSION_FORBIDDEN", "该对话属于其他用户，无法访问。conversationId=" + conversationId);
        }
        return session;
    }

    @Override
    public List<Message> loadHistoryForModel(String conversationId, int maxMessages) {
        if (!StringUtils.hasText(conversationId) || maxMessages <= 0) {
            return List.of();
        }
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getConversationId, conversationId)
                .in(ChatMessageEntity::getRole, MODEL_WINDOW_ROLES)
                .eq(ChatMessageEntity::getStatus, STATUS_DONE)
                .orderByDesc(ChatMessageEntity::getId)
                .last("LIMIT " + maxMessages);
        List<ChatMessageEntity> rows = chatMessageMapper.selectList(wrapper);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        // Rows come newest-first; the model expects oldest-first.
        Collections.reverse(rows);
        List<Message> messages = new ArrayList<>(rows.size());
        for (ChatMessageEntity row : rows) {
            Message message = toSpringMessage(row);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    @Override
    @Transactional
    public int appendUserMessage(ChatSessionEntity session, String content) {
        ChatMessageEntity row = new ChatMessageEntity();
        row.setSessionId(session.getId());
        row.setConversationId(session.getConversationId());
        row.setRole("user");
        row.setContent(limitContent(content, USER_CONTENT_MAX_LENGTH));
        row.setStatus(STATUS_DONE);
        int seq = nextSeq(session);
        row.setSeq(seq);
        chatMessageMapper.insert(row);
        touchSession(session, 1);
        applyRetention(session);
        return seq;
    }

    @Override
    @Transactional
    public void appendToolMessage(ChatSessionEntity session, String content) {
        ChatMessageEntity row = new ChatMessageEntity();
        row.setSessionId(session.getId());
        row.setConversationId(session.getConversationId());
        row.setRole("tool");
        row.setContent(limitContent(content, TOOL_CONTENT_MAX_LENGTH));
        row.setStatus(STATUS_DONE);
        row.setSeq(nextSeq(session));
        chatMessageMapper.insert(row);
        touchSession(session, 1);
        applyRetention(session);
    }

    @Override
    @Transactional
    public void appendAssistantMessage(ChatSessionEntity session,
                                       String content,
                                       String providerCode,
                                       String modelName,
                                       String status,
                                       String errorMessage,
                                       Integer tokenCount) {
        ChatMessageEntity row = new ChatMessageEntity();
        row.setSessionId(session.getId());
        row.setConversationId(session.getConversationId());
        row.setRole("assistant");
        row.setContent(limitContent(content, ASSISTANT_CONTENT_MAX_LENGTH));
        row.setProviderCode(providerCode);
        row.setModelName(modelName);
        row.setStatus(StringUtils.hasText(status) ? status : STATUS_DONE);
        row.setErrorMessage(truncate(errorMessage, ERROR_MESSAGE_MAX_LENGTH));
        row.setTokenCount(tokenCount);
        row.setSeq(nextSeq(session));
        chatMessageMapper.insert(row);
        touchSession(session, 1);
        applyRetention(session);
    }

    @Override
    public ContextSnapshotTO loadContextForModel(String conversationId) {
        return loadContextForModel(conversationId, null);
    }

    @Override
    public ContextSnapshotTO loadContextForModel(String conversationId, Integer exclusiveAboveSeq) {
        if (!StringUtils.hasText(conversationId)) {
            return new ContextSnapshotTO(null, null, 0, List.of());
        }
        ChatSessionEntity session = findSessionByConversationId(conversationId);
        if (session == null) {
            return new ContextSnapshotTO(null, null, 0, List.of());
        }
        int upto = session.getSummaryUptoSeq() == null ? 0 : session.getSummaryUptoSeq();
        AgentProperties.Memory mem = agentProperties.getMemory();
        int k = mem.getWindowMessages();
        int budget = mem.getWindowTokens();

        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getConversationId, conversationId)
                .in(ChatMessageEntity::getRole, MODEL_WINDOW_ROLES)
                .eq(ChatMessageEntity::getStatus, STATUS_DONE)
                .gt(ChatMessageEntity::getSeq, upto);
        if (exclusiveAboveSeq != null) {
            // Exclude the current (just-appended) turn so the caller can supply it exactly once.
            wrapper.lt(ChatMessageEntity::getSeq, exclusiveAboveSeq);
        }
        wrapper.orderByDesc(ChatMessageEntity::getSeq)
                .last("LIMIT " + k);
        List<ChatMessageEntity> rows = chatMessageMapper.selectList(wrapper);
        List<Message> recent = new ArrayList<>();
        if (rows != null && !rows.isEmpty()) {
            int used = 0;
            List<ChatMessageEntity> kept = new ArrayList<>();
            for (ChatMessageEntity row : rows) {
                int tokens = TokenEstimator.estimate(row.getContent());
                if (!kept.isEmpty() && used + tokens > budget) {
                    break; // token budget exhausted; older turns rely on the summary
                }
                kept.add(row);
                used += tokens;
            }
            Collections.reverse(kept);
            for (ChatMessageEntity row : kept) {
                Message message = toSpringMessage(row);
                if (message != null) {
                    recent.add(message);
                }
            }
        }
        return new ContextSnapshotTO(
                session.getRollingSummary(), session.getSummaryFacts(), upto, recent);
    }

    @Override
    public AgentMemoryVO getMemorySnapshot(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return new AgentMemoryVO(conversationId, null, List.of(), 0, 0, 0, 0, null, 0L);
        }
        ChatSessionEntity session = findSessionByConversationId(conversationId);
        if (session == null) {
            return new AgentMemoryVO(conversationId, null, List.of(), 0, 0, 0, 0, null, 0L);
        }
        AgentProperties.Memory mem = agentProperties.getMemory();
        long total = session.getMessageCount() == null ? 0L : session.getMessageCount().longValue();
        return new AgentMemoryVO(
                conversationId,
                session.getRollingSummary(),
                parseFacts(session.getSummaryFacts()),
                session.getSummaryUptoSeq(),
                mem.getWindowMessages(),
                mem.getWindowTokens(),
                session.getCompressCount(),
                session.getLastCompressedAt(),
                total);
    }

    @Override
    public List<ChatMessageEntity> readTurns(String conversationId, int fromSeq, int toSeq) {
        return readRoles(conversationId, fromSeq, toSeq, MODEL_WINDOW_ROLES);
    }

    @Override
    public List<ChatMessageEntity> readTurnsWithToolResults(String conversationId, int fromSeq, int toSeq) {
        return readRoles(conversationId, fromSeq, toSeq, COMPRESSIBLE_ROLES);
    }

    /**
     * Shared seq-range read. Always restricted to {@code DONE} rows so a partially streamed or
     * errored turn can neither be replayed to the model nor promoted into a compressed "hard fact".
     */
    private List<ChatMessageEntity> readRoles(
            String conversationId, int fromSeq, int toSeq, List<String> roles) {
        if (!StringUtils.hasText(conversationId) || toSeq < fromSeq) {
            return List.of();
        }
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getConversationId, conversationId)
                .in(ChatMessageEntity::getRole, roles)
                .eq(ChatMessageEntity::getStatus, STATUS_DONE)
                .ge(ChatMessageEntity::getSeq, fromSeq)
                .le(ChatMessageEntity::getSeq, toSeq)
                .orderByAsc(ChatMessageEntity::getSeq);
        List<ChatMessageEntity> rows = chatMessageMapper.selectList(wrapper);
        return rows == null ? List.of() : rows;
    }

    @Override
    public ChatSessionEntity peekSession(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return null;
        }
        return findSessionByConversationId(conversationId);
    }

    @Override
    public void updateSession(ChatSessionEntity session) {
        if (session != null && session.getId() != null) {
            chatSessionMapper.updateById(session);
        }
    }

    @Override
    @Transactional
    public void updateSessionTitleIfBlank(ChatSessionEntity session, String firstUserMessage) {
        if (StringUtils.hasText(session.getTitle()) || !StringUtils.hasText(firstUserMessage)) {
            return;
        }
        String trimmed = firstUserMessage.trim();
        String title = trimmed.length() <= TITLE_MAX_LENGTH ? trimmed : trimmed.substring(0, TITLE_MAX_LENGTH);
        session.setTitle(title);
        chatSessionMapper.updateById(session);
    }

    @Override
    public List<ChatSessionVO> listSessions(CurrentUserTO user) {
        List<ChatSessionEntity> sessions = chatSessionMapper.selectList(
                new LambdaQueryWrapper<ChatSessionEntity>()
                        .eq(ChatSessionEntity::getUserId, user.userId())
                        .orderByDesc(ChatSessionEntity::getLastMessageAt)
                        .orderByDesc(ChatSessionEntity::getId)
                        .last("LIMIT 200"));
        return sessions.stream()
                .map(session -> new ChatSessionVO(
                        session.getConversationId(),
                        StringUtils.hasText(session.getTitle()) ? session.getTitle() : "新对话",
                        session.getProviderCode(),
                        session.getMessageCount(),
                        session.getLastMessageAt(),
                        session.getCreatedAt()))
                .toList();
    }

    @Override
    public List<ChatMessageVO> listMessages(String conversationId, CurrentUserTO user) {
        requireOwnedSession(conversationId);
        List<ChatMessageEntity> rows = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getConversationId, conversationId)
                        .in(ChatMessageEntity::getRole, MODEL_WINDOW_ROLES)
                        .orderByAsc(ChatMessageEntity::getSeq));
        return rows.stream()
                .map(row -> new ChatMessageVO(
                        row.getSeq(),
                        row.getRole(),
                        row.getContent(),
                        row.getProviderCode(),
                        row.getModelName(),
                        row.getStatus(),
                        row.getErrorMessage(),
                        row.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional
    public void deleteSession(String conversationId, CurrentUserTO user) {
        ChatSessionEntity session = requireOwnedSession(conversationId);
        // Logic delete via the @TableLogic flag: the sidebar and context loading stop seeing it,
        // while the physical rows remain for audit.
        chatSessionMapper.deleteById(session.getId());
    }

    // ---------- helpers ----------

    private int nextSeq(ChatSessionEntity session) {
        int current = session.getMessageCount() == null ? 0 : session.getMessageCount().intValue();
        return current + 1;
    }

    private List<AgentMemoryVO.SummaryFactVO> parseFacts(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, AgentMemoryVO.SummaryFactVO.class));
        } catch (Exception exception) {
            return List.of();
        }
    }

    private ChatSessionEntity findSessionByConversationId(String conversationId) {
        LambdaQueryWrapper<ChatSessionEntity> wrapper = new LambdaQueryWrapper<ChatSessionEntity>()
                .eq(ChatSessionEntity::getConversationId, conversationId)
                .last("LIMIT 1");
        return chatSessionMapper.selectOne(wrapper);
    }

    private ChatSessionEntity createSession(String conversationId, String providerCode, CurrentUserTO currentUser) {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setConversationId(conversationId.trim());
        session.setUserId(currentUser.userId());
        session.setTenantId(currentUser.tenantId());
        session.setProviderCode(StringUtils.hasText(providerCode) ? providerCode.trim() : null);
        session.setStatus(STATUS_SESSION_ACTIVE);
        session.setMessageCount(Integer.valueOf(0));
        chatSessionMapper.insert(session);
        log.atInfo()
                .addKeyValue("conversationId", conversationId)
                .addKeyValue("userId", currentUser.userId())
                .addKeyValue("providerCode", session.getProviderCode())
                .log("Chat session created");
        return session;
    }

    private void touchSession(ChatSessionEntity session, int addedMessages) {
        int current = session.getMessageCount() == null ? 0 : session.getMessageCount().intValue();
        session.setMessageCount(Integer.valueOf(current + addedMessages));
        session.setLastMessageAt(OffsetDateTime.now());
        chatSessionMapper.updateById(session);
    }

    private Message toSpringMessage(ChatMessageEntity row) {
        String content = row.getContent() == null ? "" : row.getContent();
        String role = row.getRole();
        if ("user".equals(role)) {
            return new UserMessage(content);
        }
        if ("assistant".equals(role)) {
            return new AssistantMessage(content);
        }
        if ("system".equals(role)) {
            return new SystemMessage(content);
        }
        // Tool rows never reach this method (the window query excludes them); skip rather than
        // fabricate a chat message that would desynchronise Spring AI's tool-call pairing.
        return null;
    }

    private static String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String singleLine = value.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= maxLength ? singleLine : singleLine.substring(0, maxLength);
    }

    private void applyRetention(ChatSessionEntity session) {
        int maxMessages = agentProperties.getPersistenceMaxMessages();
        if (maxMessages < 1) {
            throw new BusinessException("CHAT_RETENTION_INVALID", "persistenceMaxMessages 必须大于 0");
        }
        int total = session.getMessageCount() == null ? 0 : session.getMessageCount();
        int redactUpto = total - maxMessages;
        if (redactUpto <= 0) {
            return;
        }
        LambdaUpdateWrapper<ChatMessageEntity> update = new LambdaUpdateWrapper<>();
        update.eq(ChatMessageEntity::getConversationId, session.getConversationId())
                .le(ChatMessageEntity::getSeq, redactUpto)
                .ne(ChatMessageEntity::getContent, RETAINED_CONTENT_MARKER)
                .set(ChatMessageEntity::getContent, RETAINED_CONTENT_MARKER)
                .set(ChatMessageEntity::getErrorMessage, null)
                .set(ChatMessageEntity::getTokenCount, null);
        chatMessageMapper.update(null, update);
    }

    private static String limitContent(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n[TRUNCATED_BY_PERSISTENCE_POLICY]";
    }
}
