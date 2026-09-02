package com.rcdis.agent.infrastructure.feishu;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.config.FeishuProperties;
import com.rcdis.agent.infrastructure.feishu.dto.FeishuTextContent;
import com.rcdis.agent.infrastructure.feishu.dto.FeishuWebhookRequest;
import com.rcdis.agent.infrastructure.feishu.dto.FeishuWebhookResponse;
import com.rcdis.agent.to.FeishuMessageTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("'${rcdis.feishu.enabled:false}' == 'true' && '${rcdis.feishu.client-type:webhook}' == 'webhook'")
public class FeishuWebhookBotClient implements FeishuBotClient {

    private static final String TEXT_MESSAGE_TYPE = "text";
    private static final String INTERACTIVE_MESSAGE_TYPE = "interactive";

    private final FeishuProperties properties;
    private final RestClient.Builder restClientBuilder;

    @Override
    public void sendMessage(FeishuMessageTO message) {
        validateConfiguration();
        FeishuWebhookRequest request = buildWebhookRequest(message);
        sendWithRetries(request, message);
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(properties.getWebhookUrl())) {
            throw new BusinessException(
                    "FEISHU_WEBHOOK_NOT_CONFIGURED",
                    "Feishu webhook URL is required when Feishu integration is enabled");
        }
        if (properties.getMaxAttempts() < 1) {
            throw new BusinessException(
                    "FEISHU_RETRY_NOT_CONFIGURED",
                    "Feishu max attempts must be greater than 0");
        }
    }

    private FeishuWebhookRequest buildWebhookRequest(FeishuMessageTO message) {
        if (!StringUtils.hasText(message.messageType())) {
            throw new BusinessException(
                    "FEISHU_MESSAGE_TYPE_EMPTY",
                    "Feishu message type must not be blank");
        }
        String messageType = message.messageType().toLowerCase(Locale.ROOT);
        String timestamp = resolveTimestamp();
        String sign = resolveSign(timestamp);
        if (TEXT_MESSAGE_TYPE.equals(messageType)) {
            String text = resolveText(message);
            return new FeishuWebhookRequest(timestamp, sign, TEXT_MESSAGE_TYPE, new FeishuTextContent(text));
        }
        if (INTERACTIVE_MESSAGE_TYPE.equals(messageType)) {
            return new FeishuWebhookRequest(timestamp, sign, INTERACTIVE_MESSAGE_TYPE, resolveCard(message));
        }
        throw new BusinessException(
                "FEISHU_MESSAGE_TYPE_UNSUPPORTED",
                "Only text and interactive Feishu webhook messages are supported. messageType=" + messageType);
    }

    private String resolveText(FeishuMessageTO message) {
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
        return value.trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveCard(FeishuMessageTO message) {
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
        return (Map<String, Object>) cardMap;
    }

    private String resolveTimestamp() {
        if (!StringUtils.hasText(properties.getSigningSecret())) {
            return null;
        }
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private String resolveSign(String timestamp) {
        if (!StringUtils.hasText(timestamp)) {
            return null;
        }
        return FeishuSignSupport.sign(timestamp, properties.getSigningSecret());
    }

    private void sendWithRetries(FeishuWebhookRequest request, FeishuMessageTO message) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                sendOnce(request);
                log.atInfo()
                        .addKeyValue("messageType", message.messageType())
                        .addKeyValue("target", message.target())
                        .addKeyValue("idempotencyKey", message.idempotencyKey())
                        .addKeyValue("attempt", attempt)
                        .log("Feishu webhook message sent");
                return;
            } catch (RestClientResponseException exception) {
                lastException = new BusinessException(
                        "FEISHU_WEBHOOK_HTTP_FAILED",
                        "Feishu webhook HTTP failed, statusCode=%s, responseBody=%s"
                                .formatted(exception.getStatusCode(), sanitizeResponseBody(exception.getResponseBodyAsString())),
                        exception);
                logRetryWarning(message, attempt, lastException);
            } catch (RestClientException exception) {
                lastException = new BusinessException(
                        "FEISHU_WEBHOOK_REQUEST_FAILED",
                        "Feishu webhook request failed, message=%s".formatted(exception.getMessage()),
                        exception);
                logRetryWarning(message, attempt, lastException);
            }
        }
        throw lastException;
    }

    private void sendOnce(FeishuWebhookRequest request) {
        ResponseEntity<FeishuWebhookResponse> response = restClientBuilder.build()
                .post()
                .uri(properties.getWebhookUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(FeishuWebhookResponse.class);
        FeishuWebhookResponse responseBody = response.getBody();
        if (responseBody == null || !responseBody.isSuccessful()) {
            String code = responseBody == null ? "null" : String.valueOf(responseBody.code());
            String message = responseBody == null ? "empty response body" : responseBody.msg();
            throw new BusinessException(
                    "FEISHU_WEBHOOK_REJECTED",
                    "Feishu webhook rejected request, code=%s, message=%s".formatted(code, message));
        }
    }

    private void logRetryWarning(FeishuMessageTO message, int attempt, RuntimeException exception) {
        log.atWarn()
                .setCause(exception)
                .addKeyValue("messageType", message.messageType())
                .addKeyValue("target", message.target())
                .addKeyValue("idempotencyKey", message.idempotencyKey())
                .addKeyValue("attempt", attempt)
                .addKeyValue("maxAttempts", properties.getMaxAttempts())
                .log("Feishu webhook send attempt failed");
    }

    private String sanitizeResponseBody(String responseBody) {
        if (responseBody == null) {
            return "";
        }
        String trimmed = responseBody.trim();
        if (trimmed.length() <= 500) {
            return trimmed;
        }
        return trimmed.substring(0, 500);
    }
}
