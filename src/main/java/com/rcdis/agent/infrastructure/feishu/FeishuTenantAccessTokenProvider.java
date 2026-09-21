package com.rcdis.agent.infrastructure.feishu;

import java.time.Clock;
import java.time.Instant;

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
import com.rcdis.agent.infrastructure.feishu.dto.FeishuTenantAccessTokenRequest;
import com.rcdis.agent.infrastructure.feishu.dto.FeishuTenantAccessTokenResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Supplies cached tenant access tokens for Feishu OpenAPI calls made by this application.
 *
 * <p>Extracted from the app bot client so that message sending and read-only queries such as
 * listing chat members share one cache and one refresh policy. The token is never logged.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("'${rcdis.feishu.enabled:false}' == 'true' && '${rcdis.feishu.client-type:webhook}' == 'app'")
public class FeishuTenantAccessTokenProvider {

    private static final String TENANT_TOKEN_PATH = "/open-apis/auth/v3/tenant_access_token/internal";

    private final FeishuProperties properties;
    private final RestClient.Builder restClientBuilder;
    private final Clock clock = Clock.systemUTC();
    private volatile CachedTenantAccessToken cachedTenantAccessToken;

    public String getToken() {
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

    /**
     * Base URL without a trailing slash, so callers can append absolute API paths.
     */
    public String normalizeBaseUrl() {
        if (!StringUtils.hasText(properties.getOpenApiBaseUrl())) {
            throw new BusinessException(
                    "FEISHU_OPEN_API_BASE_URL_NOT_CONFIGURED",
                    "Feishu OpenAPI base URL is required when app bot integration is enabled");
        }
        String baseUrl = properties.getOpenApiBaseUrl().trim();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public int maxAttempts() {
        if (properties.getMaxAttempts() < 1) {
            throw new BusinessException(
                    "FEISHU_RETRY_NOT_CONFIGURED",
                    "Feishu max attempts must be greater than 0");
        }
        return properties.getMaxAttempts();
    }

    private CachedTenantAccessToken fetchTenantAccessToken() {
        validateAppCredentials();
        FeishuTenantAccessTokenRequest request = new FeishuTenantAccessTokenRequest(
                properties.getAppId(),
                properties.getAppSecret());
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= maxAttempts(); attempt++) {
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
                                .formatted(exception.getStatusCode(),
                                        FeishuResponseSanitizer.sanitize(exception.getResponseBodyAsString())),
                        exception);
                logRetryWarning(attempt, lastException);
            } catch (RestClientException exception) {
                lastException = new BusinessException(
                        "FEISHU_TENANT_TOKEN_REQUEST_FAILED",
                        "Feishu tenant token request failed, message=%s".formatted(exception.getMessage()),
                        exception);
                logRetryWarning(attempt, lastException);
            }
        }
        throw lastException;
    }

    private void validateAppCredentials() {
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
    }

    private CachedTenantAccessToken parseTenantAccessToken(FeishuTenantAccessTokenResponse response) {
        if (response == null || !response.isSuccessful() || !StringUtils.hasText(response.tenantAccessToken())) {
            String code = response == null ? "null" : String.valueOf(response.code());
            String message = response == null ? "empty response body" : response.msg();
            throw new BusinessException(
                    "FEISHU_TENANT_TOKEN_REJECTED",
                    "Feishu tenant token request rejected, code=%s, message=%s".formatted(code, message));
        }
        Integer expire = response.expire();
        if (expire == null || expire <= 0) {
            throw new BusinessException(
                    "FEISHU_TENANT_TOKEN_EXPIRE_INVALID",
                    "Feishu tenant token response has invalid expire value");
        }
        long refreshSkewSeconds = properties.getTenantTokenRefreshSkewSeconds();
        Instant expiresAt = clock.instant().plusSeconds(Math.max(1L, expire - refreshSkewSeconds));
        log.atInfo()
                .addKeyValue("expiresInSeconds", expire)
                .addKeyValue("refreshSkewSeconds", refreshSkewSeconds)
                .log("Feishu tenant access token refreshed");
        return new CachedTenantAccessToken(response.tenantAccessToken(), expiresAt);
    }

    private void logRetryWarning(int attempt, RuntimeException exception) {
        log.atWarn()
                .setCause(exception)
                .addKeyValue("attempt", attempt)
                .addKeyValue("maxAttempts", properties.getMaxAttempts())
                .log("Feishu tenant access token attempt failed");
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
