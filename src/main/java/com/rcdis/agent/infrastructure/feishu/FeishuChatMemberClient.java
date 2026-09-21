package com.rcdis.agent.infrastructure.feishu;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.to.FeishuChatMemberTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads the membership of a Feishu group chat so that an administrator can bind approvers by
 * picking people instead of copying open_id values by hand.
 *
 * <p>Requires the application to hold a chat membership read scope such as
 * {@code im:chat:readonly}. Member names are personal data: they are returned to the caller for
 * display but are never written to application logs.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("'${rcdis.feishu.enabled:false}' == 'true' && '${rcdis.feishu.client-type:webhook}' == 'app'")
public class FeishuChatMemberClient {

    private static final String MEMBERS_PATH = "/open-apis/im/v1/chats/{chatId}/members";
    private static final int PAGE_SIZE = 100;
    /** Bounds the walk so a very large group cannot turn into an unbounded request loop. */
    private static final int MAX_PAGES = 20;

    private final FeishuTenantAccessTokenProvider tokenProvider;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    public List<FeishuChatMemberTO> listMembers(String chatId) {
        if (!StringUtils.hasText(chatId)) {
            throw new BusinessException(
                    "FEISHU_CHAT_ID_REQUIRED",
                    "Listing chat members requires a chat id. Set rcdis.feishu.default-receive-id to the approval "
                            + "group's oc_ identifier.");
        }
        List<FeishuChatMemberTO> members = new ArrayList<>();
        String pageToken = null;
        int reportedTotal = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode data = fetchPage(chatId.trim(), pageToken);
            reportedTotal = data.path("member_total").asInt(0);
            collectMembers(data, members);
            if (!data.path("has_more").asBoolean(false)) {
                break;
            }
            pageToken = data.path("page_token").asText(null);
            if (!StringUtils.hasText(pageToken)) {
                break;
            }
        }
        // A non-zero total with nothing collected means the response shape changed under us, which is
        // worth surfacing instead of silently reporting an empty group.
        if (members.isEmpty() && reportedTotal > 0) {
            log.atWarn()
                    .addKeyValue("memberTotal", reportedTotal)
                    .log("Feishu reported chat members but none could be parsed; check the items field name");
        }
        log.atInfo()
                .addKeyValue("memberCount", members.size())
                .addKeyValue("memberTotal", reportedTotal)
                .log("Feishu chat members listed");
        return List.copyOf(members);
    }

    private JsonNode fetchPage(String chatId, String pageToken) {
        String url = UriComponentsBuilder
                .fromUriString(tokenProvider.normalizeBaseUrl() + MEMBERS_PATH)
                .buildAndExpand(chatId)
                .toUriString();
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url)
                .queryParam("member_id_type", "open_id")
                .queryParam("page_size", PAGE_SIZE);
        if (StringUtils.hasText(pageToken)) {
            builder.queryParam("page_token", pageToken);
        }
        try {
            String body = restClientBuilder.build()
                    .get()
                    .uri(builder.build().toUri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.getToken())
                    .retrieve()
                    .body(String.class);
            return readData(objectMapper, body);
        } catch (RestClientResponseException exception) {
            throw new BusinessException(
                    "FEISHU_CHAT_MEMBERS_HTTP_FAILED",
                    "Feishu chat member query failed, statusCode=%s, responseBody=%s".formatted(
                            exception.getStatusCode(),
                            FeishuResponseSanitizer.sanitize(exception.getResponseBodyAsString())),
                    exception);
        } catch (RestClientException exception) {
            throw new BusinessException(
                    "FEISHU_CHAT_MEMBERS_REQUEST_FAILED",
                    "Feishu chat member query failed, message=%s".formatted(exception.getMessage()),
                    exception);
        }
    }

    /**
     * Parses one Feishu chat member page body. Static and dependency free so that the exact wire
     * shape can be pinned by a test: guessing the field name once already produced a silently empty
     * approver picker.
     */
    static List<FeishuChatMemberTO> parseMembers(ObjectMapper mapper, String body) {
        List<FeishuChatMemberTO> members = new ArrayList<>();
        collectMembers(readData(mapper, body), members);
        return List.copyOf(members);
    }

    private static JsonNode readData(ObjectMapper mapper, String body) {
        if (!StringUtils.hasText(body)) {
            throw new BusinessException("FEISHU_CHAT_MEMBERS_EMPTY_RESPONSE", "Feishu returned an empty body");
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException exception) {
            throw new BusinessException(
                    "FEISHU_CHAT_MEMBERS_RESPONSE_INVALID",
                    "Feishu chat member response is not valid JSON",
                    exception);
        }
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            // A non-zero code usually means the app lacks the chat membership read scope.
            throw new BusinessException(
                    "FEISHU_CHAT_MEMBERS_REJECTED",
                    "Feishu rejected the chat member query, code=%s, msg=%s".formatted(
                            code, root.path("msg").asText("")));
        }
        return root.path("data");
    }

    /**
     * Feishu returns the page under {@code data.items}; {@code members} is accepted as a fallback so
     * a field rename degrades into a warning rather than an empty approver picker.
     */
    private static void collectMembers(JsonNode data, List<FeishuChatMemberTO> target) {
        JsonNode items = data.path("items");
        if (!items.isArray()) {
            items = data.path("members");
        }
        if (!items.isArray()) {
            return;
        }
        for (JsonNode node : items) {
            String openId = node.path("member_id").asText(null);
            if (!StringUtils.hasText(openId)) {
                continue;
            }
            target.add(new FeishuChatMemberTO(
                    openId,
                    node.path("name").asText(""),
                    node.path("tenant_key").asText(null)));
        }
    }
}
