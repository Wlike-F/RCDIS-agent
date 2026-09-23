package com.rcdis.agent;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.service.ChatHistoryService;
import com.rcdis.agent.service.JwtTokenService;
import com.rcdis.agent.support.TestAuth;

/**
 * The session REST API is the source of truth for the sidebar: only the owner sees the session,
 * internal tool rows never leak, and a deleted conversation leaves the list.
 */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "rcdis.feishu.enabled=false",
                "rcdis.feishu.client-type=noop"
        })
class ChatSessionApiTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private ChatHistoryService chatHistoryService;

    @BeforeEach
    void setUp() {
        TestAuth.applyBearer(restTemplate, TestAuth.researcherToken(jwtTokenService));
    }

    @AfterEach
    void clearUser() {
        CurrentUserContextHolder.clear();
    }

    @Test
    void sessionsAndMessagesAreOwnerScopedAndToolRowsNeverLeak() {
        String conversationId = "conv-api-" + UUID.randomUUID();
        CurrentUserTO owner = CurrentUserTO.of(
                "sess-user-1", "sess-user-1", "test", conversationId, Set.of("RESEARCHER"));

        CurrentUserContextHolder.set(owner);
        var session = chatHistoryService.resolveSession(conversationId, null);
        chatHistoryService.appendUserMessage(session, "帮我看看这个月的支出");
        chatHistoryService.updateSessionTitleIfBlank(session, "帮我看看这个月的支出");
        chatHistoryService.appendToolMessage(session, "{\"ok\":true,\"tool\":\"summarize_expenses\"}");
        chatHistoryService.appendAssistantMessage(
                session, "本月已入账支出合计 180.00 元。", "code99", "gpt-5.5", "DONE", null, 42);

        JsonNode sessions = data(
                exchange(HttpMethod.GET, "/api/chat/sessions?_" + UUID.randomUUID(), owner));
        JsonNode mine = findConversation(sessions, conversationId);
        assertThat(mine).isNotNull();
        assertThat(mine.path("title").asText()).contains("帮我看看这个月的支出");
        assertThat(mine.path("messageCount").asInt()).isGreaterThanOrEqualTo(3);

        JsonNode messages = data(exchange(
                HttpMethod.GET, "/api/chat/sessions/" + conversationId + "/messages", owner));
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(0).path("content").asText()).contains("这个月的支出");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(1).path("modelName").asText()).isEqualTo("gpt-5.5");

        // Another user must neither see the session in the list nor read its messages.
        CurrentUserTO stranger = CurrentUserTO.of(
                "sess-user-2", "sess-user-2", "test", null, Set.of("RESEARCHER"));
        JsonNode otherList = data(exchange(HttpMethod.GET, "/api/chat/sessions", stranger));
        assertThat(findConversation(otherList, conversationId)).isNull();
        ResponseEntity<String> otherRead = exchange(
                HttpMethod.GET, "/api/chat/sessions/" + conversationId + "/messages", stranger);
        assertThat(otherRead.getStatusCode().value()).isEqualTo(400);

        // Owner deletes the conversation: it leaves the list for good.
        TestAuth.applyBearer(restTemplate, TestAuth.researcherToken(jwtTokenService));
        ResponseEntity<String> deleted = exchange(
                HttpMethod.DELETE, "/api/chat/sessions/" + conversationId, owner);
        assertThat(deleted.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode afterDelete = data(
                exchange(HttpMethod.GET, "/api/chat/sessions?_" + UUID.randomUUID(), owner));
        assertThat(findConversation(afterDelete, conversationId)).isNull();
    }

    // ---------- helpers ----------

    private JsonNode data(ResponseEntity<String> response) {
        return parse(response).path("data");
    }

    private ResponseEntity<String> exchange(HttpMethod method, String url, CurrentUserTO viewer) {
        TestAuth.applyBearer(restTemplate, TestAuth.token(
                jwtTokenService, viewer.userId(), "test", "RESEARCHER"));
        return restTemplate.exchange(url, method, new HttpEntity<>(new HttpHeaders()), String.class);
    }

    private JsonNode parse(ResponseEntity<String> response) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    response.getBody() == null ? "{}" : response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("响应不是合法 JSON", exception);
        }
    }

    private JsonNode findConversation(JsonNode sessions, String conversationId) {
        for (JsonNode item : sessions) {
            if (conversationId.equals(item.path("conversationId").asText())) {
                return item;
            }
        }
        return null;
    }
}
