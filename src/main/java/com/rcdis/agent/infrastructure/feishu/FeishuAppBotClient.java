package com.rcdis.agent.infrastructure.feishu;

import java.util.Locale;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.config.FeishuProperties;
import com.rcdis.agent.infrastructure.feishu.dto.FeishuSendMessageRequest;
import com.rcdis.agent.infrastructure.feishu.dto.FeishuSendMessageResponse;
import com.rcdis.agent.infrastructure.feishu.dto.FeishuTextContent;
import com.rcdis.agent.to.FeishuMessageTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends messages through a Feishu custom application bot.
 *
 * <p>Supports {@code text} and {@code interactive} (card) messages. Token acquisition and caching
 * live in {@link FeishuTenantAccessTokenProvider} so that read-only OpenAPI calls share it.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("'${rcdis.feishu.enabled:false}' == 'true' && '${rcdis.feishu.client-type:webhook}' == 'app'")
public class FeishuAppBotClient implements FeishuBotClient {

    private static final String TEXT_MESSAGE_TYPE = "text";
    private static final String INTERACTIVE_MESSAGE_TYPE = "interactive";
    private static final String SEND_MESSAGE_PATH = "/open-apis/im/v1/messages";

    private final FeishuProperties properties;
    private final FeishuTenantAccessTokenProvider tenantAccessTokenProvider;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public void sendMessage(FeishuMessageTO message) {
        if (properties.getMaxAttempts() < 1) {
            throw new BusinessException(
                    "FEISHU_RETRY_NOT_CONFIGURED",
                    "Feishu max attempts must be greater than 0");
        }
        String tenantAccessToken = tenantAccessTokenProvider.getToken();
        FeishuSendMessageRequest request = buildSendMessageRequest(message);
        sendWithRetries(request, tenantAccessToken, message);
    }

    private FeishuSendMessageRequest buildSendMessageRequest(FeishuMessageTO message) {
        String messageType = resolveMessageType(message);
        String receiveId = resolveReceiveId(message);
        String content = serializeContent(message, messageType);
        return new FeishuSendMessageRequest(receiveId, messageType, content);
    }

    private String resolveMessageType(FeishuMessageTO message) {
        if (!StringUtils.hasText(message.messageType())) {
            throw new BusinessException(
                    "FEISHU_MESSAGE_TYPE_EMPTY",
                    "Feishu message type must not be blank");
        }
        String messageType = message.messageType().toLowerCase(Locale.ROOT);
        if (!TEXT_MESSAGE_TYPE.equals(messageType) && !INTERACTIVE_MESSAGE_TYPE.equals(messageType)) {
            throw new BusinessException(
                    "FEISHU_MESSAGE_TYPE_UNSUPPORTED",
                    "Only text and interactive Feishu app bot messages are supported. messageType=" + messageType);
        }
        return messageType;
    }

    private String resolveReceiveId(FeishuMessageTO message) {
        if (StringUtils.hasText(message.target())) {
            return message.target().trim();
        }
        if (StringUtils.hasText(properties.getDefaultReceiveId())) {
            return properties.getDefaultReceiveId().trim();
        }
        throw new BusinessException(
                "FEISHU_RECEIVE_ID_NOT_CONFIGURED",
                "Feishu app bot message requires target or rcdis.feishu.default-receive-id");
    }

    private String resolveReceiveIdType(FeishuMessageTO message) {
        if (message.payload() != null) {
            Object receiveIdType = message.payload().get("receiveIdType");
            if (receiveIdType instanceof String value && StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        if (StringUtils.hasText(properties.getDefaultReceiveIdType())) {
            return properties.getDefaultReceiveIdType().trim();
        }
        throw new BusinessException(
                "FEISHU_RECEIVE_ID_TYPE_NOT_CONFIGURED",
                "Feishu app bot message requires receiveIdType or rcdis.feishu.default-receive-id-type");
    }

    private String serializeContent(FeishuMessageTO message, String messageType) {
        if (INTERACTIVE_MESSAGE_TYPE.equals(messageType)) {
            return serializeCardContent(message);
        }
        return serializeTextContent(message);
    }

    private String serializeTextContent(FeishuMessageTO message) {
        if (message.payload() == null) {
            throw new BusinessException(
                    "FEISHU_PAYLOAD_EMPTY",
                    "Feishu message payload must not be null");
        }
        Object text = message.payload().get("text");
        if (!(text instanceof String value) || !StringUtils.hasText(value)) {
            throw new BusinessException(
                    "FEISHU_TEXT_EMPTY",
                    "Feishu text message payload must contain a non-blank text field");
        }
        try {
            return objectMapper.writeValueAsString(new FeishuTextContent(value.trim()));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "FEISHU_TEXT_CONTENT_SERIALIZE_FAILED",
                    "Failed to serialize Feishu text message content",
                    exception);
        }
    }

    private String serializeCardContent(FeishuMessageTO message) {
        if (message.payload() == null) {
            throw new BusinessException(
                    "FEISHU_PAYLOAD_EMPTY",
                    "Feishu message payload must not be null");
        }
        Object card = message.payload().get("card");
        if (!(card instanceof Map<?, ?> cardMap) || cardMap.isEmpty()) {
            throw new BusinessException(
                    "FEISHU_CARD_EMPTY",
                    "Feishu interactive message payload must contain a non-empty card field");
        }
        try {
            return objectMapper.writeValueAsString(cardMap);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "FEISHU_CARD_CONTENT_SERIALIZE_FAILED",
                    "Failed to serialize Feishu card message content",
                    exception);
        }
    }

    private void sendWithRetries(FeishuSendMessageRequest request, String tenantAccessToken, FeishuMessageTO message) {
        RuntimeException lastException = null;
        String receiveIdType = resolveReceiveIdType(message);
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                sendOnce(request, receiveIdType, tenantAccessToken);
                log.atInfo()
                        .addKeyValue("messageType", message.messageType())
                        .addKeyValue("receiveIdType", receiveIdType)
                        .addKeyValue("target", message.target())
                        .addKeyValue("idempotencyKey", message.idempotencyKey())
                        .addKeyValue("attempt", attempt)
                        .log("Feishu app bot message sent");
                return;
            } catch (RestClientResponseException exception) {
                lastException = new BusinessException(
                        "FEISHU_SEND_MESSAGE_HTTP_FAILED",
                        "Feishu send message HTTP failed, statusCode=%s, responseBody=%s"
                                .formatted(exception.getStatusCode(),
                                        FeishuResponseSanitizer.sanitize(exception.getResponseBodyAsString())),
                        exception);
                logSendRetryWarning(message, receiveIdType, attempt, lastException);
            } catch (RestClientException exception) {
                lastException = new BusinessException(
                        "FEISHU_SEND_MESSAGE_REQUEST_FAILED",
                        "Feishu send message request failed, message=%s".formatted(exception.getMessage()),
                        exception);
                logSendRetryWarning(message, receiveIdType, attempt, lastException);
            }
        }
        throw lastException;
    }

    private void sendOnce(FeishuSendMessageRequest request, String receiveIdType, String tenantAccessToken) {
        ResponseEntity<FeishuSendMessageResponse> response = restClientBuilder.build()
                .post()
                .uri(tenantAccessTokenProvider.normalizeBaseUrl()
                        + SEND_MESSAGE_PATH + "?receive_id_type=" + receiveIdType)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantAccessToken)
                .body(request)
                .retrieve()
                .toEntity(FeishuSendMessageResponse.class);
        FeishuSendMessageResponse responseBody = response.getBody();
        if (responseBody == null || !responseBody.isSuccessful()) {
            String code = responseBody == null ? "null" : String.valueOf(responseBody.code());
            String message = responseBody == null ? "empty response body" : responseBody.msg();
            throw new BusinessException(
                    "FEISHU_SEND_MESSAGE_REJECTED",
                    "Feishu send message request rejected, code=%s, message=%s".formatted(code, message));
        }
    }

    private void logSendRetryWarning(
            FeishuMessageTO message,
            String receiveIdType,
            int attempt,
            RuntimeException exception
    ) {
        log.atWarn()
                .setCause(exception)
                .addKeyValue("messageType", message.messageType())
                .addKeyValue("receiveIdType", receiveIdType)
                .addKeyValue("target", message.target())
                .addKeyValue("idempotencyKey", message.idempotencyKey())
                .addKeyValue("attempt", attempt)
                .addKeyValue("maxAttempts", properties.getMaxAttempts())
                .log("Feishu app bot send attempt failed");
    }
}
