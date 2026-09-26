package com.rcdis.agent;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.rcdis.agent.agent.AgentToolContext;
import com.rcdis.agent.agent.TurnTraceCollector;
import com.rcdis.agent.agent.tools.MemoryTools;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.entity.ChatMessageEntity;
import com.rcdis.agent.entity.ChatSessionEntity;
import com.rcdis.agent.service.ChatHistoryService;

/**
 * Locks the D1 contract: {@code role=tool} rows written by {@code RecordingToolCallback} must be
 * readable by the compressor (facts source) and by {@code recall_history}, while staying OUT of the
 * raw window that is replayed to the model as chat messages.
 *
 * <p>Before this contract existed, {@code readTurns} filtered {@code MODEL_VISIBLE_ROLES}
 * (user/assistant only), so tool rows were write-only: verified tool conclusions silently vanished
 * from both the compression source and the recall channel.</p>
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "rcdis.feishu.enabled=false",
        "rcdis.feishu.client-type=noop",
        "rcdis.agent.memory.recall-max-turns=20"
})
class ToolRowContextVisibilityTests {

    private static final String TOOL_RESULT = "query_project_budget -> {\"ok\":true,\"remaining\":3000.00}";

    @Autowired
    private ChatHistoryService chatHistoryService;

    @Autowired
    private MemoryTools memoryTools;

    @AfterEach
    void clearUser() {
        CurrentUserContextHolder.clear();
    }

    @Test
    void toolRowsAreReadableForCompressionAndRecallButStayOutOfTheModelWindow() {
        ChatSessionEntity session = newSession();

        chatHistoryService.appendUserMessage(session, "P-0001 还剩多少预算？");
        chatHistoryService.appendToolMessage(session, TOOL_RESULT);
        chatHistoryService.appendAssistantMessage(
                session, "剩余 3,000.00 元。", null, null, "DONE", null, null);

        // Compression / recall source: user, tool and assistant rows, ordered by seq.
        assertThat(chatHistoryService.readTurnsWithToolResults(session.getConversationId(), 1, 3))
                .extracting(ChatMessageEntity::getRole)
                .containsExactly("user", "tool", "assistant");

        // Model window source: tool rows must NOT be replayed as chat messages.
        assertThat(chatHistoryService.readTurns(session.getConversationId(), 1, 3))
                .extracting(ChatMessageEntity::getRole)
                .containsExactly("user", "assistant");
    }

    @Test
    void erroredAssistantRowsAreExcludedFromTheCompressionSource() {
        ChatSessionEntity session = newSession();

        chatHistoryService.appendUserMessage(session, "会失败的一轮");
        chatHistoryService.appendAssistantMessage(
                session, "半截输出", null, null, "ERROR", "boom", null);
        chatHistoryService.appendUserMessage(session, "重试的一轮");

        assertThat(chatHistoryService.readTurnsWithToolResults(session.getConversationId(), 1, 3))
                .extracting(ChatMessageEntity::getContent)
                .containsExactly("会失败的一轮", "重试的一轮");
    }

    @Test
    void recallHistorySurfacesTheToolResultTextSoTheModelCanVerifyConclusions() {
        ChatSessionEntity session = newSession();
        chatHistoryService.appendUserMessage(session, "P-0001 还剩多少预算？");
        chatHistoryService.appendToolMessage(session, TOOL_RESULT);
        chatHistoryService.appendAssistantMessage(
                session, "剩余 3,000.00 元。", null, null, "DONE", null, null);

        String json = memoryTools.recallHistory(1, 3, toolContext(session));

        assertThat(json).contains("\"ok\":true");
        assertThat(json).contains("query_project_budget");
        assertThat(json).contains("3000.00");
    }

    @Test
    void anOutOfRangeOrIllegalRecallStillFailsClosed() {
        ChatSessionEntity session = newSession();
        chatHistoryService.appendUserMessage(session, "只有一轮");

        assertThat(memoryTools.recallHistory(5, 2, toolContext(session))).contains("\"ok\":false");
    }

    private ChatSessionEntity newSession() {
        CurrentUserContextHolder.set(CurrentUserTO.of(
                "tool-row-user", "tool-row-user", "test", null, Set.of("RESEARCHER")));
        return chatHistoryService.resolveSession("tool-row-" + UUID.randomUUID(), null);
    }

    private ToolContext toolContext(ChatSessionEntity session) {
        AgentToolContext context = new AgentToolContext(
                null, session.getConversationId(), CurrentUserContextHolder.currentOrAnonymous(),
                new TurnTraceCollector(), session);
        return new ToolContext(AgentToolContext.asToolContextMap(context));
    }

    /** Kept to document that the model window still yields Spring AI messages without tool rows. */
    @Test
    void modelWindowSnapshotContainsNoToolRows() {
        ChatSessionEntity session = newSession();
        chatHistoryService.appendUserMessage(session, "问题");
        chatHistoryService.appendToolMessage(session, TOOL_RESULT);
        chatHistoryService.appendAssistantMessage(session, "回答", null, null, "DONE", null, null);

        List<org.springframework.ai.chat.messages.Message> window =
                chatHistoryService.loadContextForModel(session.getConversationId()).recentMessages();

        assertThat(window).hasSize(2);
        assertThat(window).noneMatch(message -> message.getText().contains("query_project_budget"));
    }
}
