package com.rcdis.agent.infrastructure.feishu;

import java.time.Clock;
import java.time.Instant;
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
import com.rcdis.agent.infrastructure.feishu.dto.FeishuTenantAccessTokenRequest;
import com.rcdis.agent.infrastructure.feishu.dto.FeishuTenantAccessTokenResponse;
import com.rcdis.agent.infrastructure.feishu.dto.FeishuTextContent;
import com.rcdis.agent.to.FeishuMessageTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("'${rcdis.feishu.enabled:false}' == 'true' && '${rcdis.feishu.client-type:webhook}' == 'app'")
public class FeishuAppBotClient implements FeishuBotClient {

    private static final String TEXT_MESSAGE_TYPE = "text";
    private static final String INTERACTIVE_MESSAGE_TYPE = "interactive";
    private static final String TENANT_TOKEN_PATH = "/open-apis/auth/v3/tenant_access_token/internal";
    private static final String SEND_MESSAGE_PATH = "/open-apis/im/v1/messages";

    private final FeishuProperties properties;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final Clock clock = Clock.systemUTC();
    private volatile CachedTenantAccessToken cachedTenantAccessToken;

    @Override
    public void sendMessage(FeishuMessageTO message) {
        validateConfiguration();
        String tenantAccessToken = resolveTenantAccessToken();
        FeishuSendMessageRequest request = buildSendMessageRequest(message);
        sendWithRetries(request, tenantAccessToken, message);
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(properties.getOpenApiBaseUrl())) {
            throw new BusinessException(
                    "FEISHU_OPEN_API_BASE_URL_NOT_CONFIGURED",
                    "Feishu OpenAPI base URL is required when app bot integration is enabled");
        }
        if (!StringUtils.hasText(properties.getAppId())) {
            throw new BusinessException(
                    "FEISHU_APP_ID_NOT_CONFIGURED",
                    "Feishu app ID is required when app bot integration is enabled");
        }
        if (!StringUtils.hasText(properties.getAppSecret())) {
            throw new BusinessException(
                    "FEISHU_APP_SECRET_NOT_CONFIGURED",
                    "Feishu app secret is required when app bot integration is enabled");
        }
        if (properties.getMaxAttempts() < 1) {
            throw new BusinessException(
                    "FEISHU_RETRY_NOT_CONFIGURED",
                    "Feishu max attempts must be greater than 0");
        }
    }

    private String resolveTenantAccessToken() {
        CachedTenantAccessToken cachedToken = cachedTenantAccessToken;
        if (cachedToken != null && cachedToken.isUsableAt(clock.instant())) {
            return cachedToken.token();
        }
        synchronized (this) {
            CachedTenantAccessToken refreshedToken = cachedTenantAccessToken;
            if (refreshedToken != null && refreshedToken.isUsableAt(clock.instant())) {
                return refreshedToken.token();
            }
            cachedTenantAccessToken = fetchTenantAccessToken();
            return cachedTenantAccessToken.token();
        }
    }

    private CachedTenantAccessToken fetchTenantAccessToken() {
        FeishuTenantAccessTokenRequest request = new FeishuTenantAccessTokenRequest(
                properties.getAppId(),
                properties.getAppSecret());
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                ResponseEntity<FeishuTenantAccessTokenResponse> response = restClientBuilder.build()
                        .post()
                        .uri(normalizeBaseUrl() + TENANT_TOKEN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toEntity(FeishuTenantAccessTokenResponse.class);
                return parseTenantAccessToken(response.getBody());
            } catch (RestClientResponseException exception) {
                lastException = new BusinessException(
                        "FEISHU_TENANT_TOKEN_HTTP_FAILED",
                        "Feishu tenant token HTTP failed, statusCode=%s, responseBody=%s"
                                .formatted(exception.getStatusCode(), sanitizeResponseBody(exception.getResponseBodyAsString())),
                        exception);
                logTenantTokenRetryWarning(attempt, lastException);
            } catch (RestClientException exception) {
                lastException = new BusinessException(
                        "FEISHU_TENANT_TOKEN_REQUEST_FAILED",
                        "Feishu tenant token request failed, message=%s".formatted(exception.getMessage()),
                        exception);
                logTenantTokenRetryWarning(attempt, lastException);
            }
        }
        throw lastException;
    }

    private CachedTenantAccessToken parseTenantAccessToken(FeishuTenantAccessTokenResponse response) {
        if (response == null || !response.isSuccessful() || !StringUtils.hasText(response.tenantAccessToken())) {
            String code = response == null ? "null" : String.valueOf(response.code());
            String message = response == null ? "empty response body" : response.msg();
            throw new BusinessException(
                    "FEISHU_TENANT_TOKEN_REJECTED",
                    "Feishu tenant token request rejected, code=%s, message=%s".formatted(code, message));
        }
        int expiresInSeconds = resolveExpiresInSeconds(response.expire());
        long refreshSkewSeconds = properties.getTenantTokenRefreshSkewSeconds();
        Instant expiresAt = clock.instant().plusSeconds(Math.max(1L, expiresInSeconds - refreshSkewSeconds));
        log.atInfo()
                .addKeyValue("expiresInSeconds", expiresInSeconds)
                .addKeyValue("refreshSkewSeconds", refreshSkewSeconds)
                .log("Feishu tenant access token refreshed");
        return new CachedTenantAccessToken(response.tenantAccessToken(), expiresAt);
    }

    private int resolveExpiresInSeconds(Integer expire) {
        if (expire == null || expire <= 0) {
            throw new BusinessException(
                    "FEISHU_TENANT_TOKEN_EXPIRE_INVALID",
                    "Feishu tenant token response has invalid expire value");
        }
        return expire;
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
                                .formatted(exception.getStatusCode(), sanitizeResponseBody(exception.getResponseBodyAsString())),
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
                .uri(normalizeBaseUrl() + SEND_MESSAGE_PATH + "?receive_id_type=" + receiveIdType)
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

    private void logTenantTokenRetryWarning(int attempt, RuntimeException exception) {
        log.atWarn()
                .setCause(exception)
                .addKeyValue("attempt", attempt)
                .addKeyValue("maxAttempts", properties.getMaxAttempts())
                .log("Feishu tenant access token attempt failed");
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

    private String normalizeBaseUrl() {
        String baseUrl = properties.getOpenApiBaseUrl().trim();
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
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

    private record CachedTenantAccessToken(
            String token,
            Instant expiresAt
    ) {

        private boolean isUsableAt(Instant now) {
            return StringUtils.hasText(token) && now.isBefore(expiresAt);
        }
    }
}
