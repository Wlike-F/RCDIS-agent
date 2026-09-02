package com.rcdis.agent.infrastructure.ai;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.to.ModelEndpointTO;
import com.rcdis.agent.to.ProviderProbeTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Client for the OpenAI compatible wire protocol.
 *
 * <p>Protocol contract implemented here, which is what a custom model endpoint must expose:</p>
 * <ul>
 *   <li>{@code GET  {baseUrl}{modelsPath}} returning {@code {"data":[{"id":"<model>"}, ...]}} or a
 *       bare JSON array of model ids. Authenticated with {@code Authorization: Bearer <apiKey>}.</li>
 *   <li>{@code POST {baseUrl}{chatCompletionsPath}} accepting
 *       {@code {"model","messages":[{"role","content"}],"max_tokens","temperature","stream"}} and
 *       returning {@code {"choices":[{"message":{"content"}}]}}.</li>
 * </ul>
 *
 * <p>This single protocol covers OpenAI, DeepSeek, DashScope compatible mode, Moonshot, Zhipu,
 * SiliconFlow, OpenRouter, Groq, Together, vLLM, LM Studio, Xinference, Ollama ({@code /v1}) and
 * One-API style gateways. Vendors that do not use {@code /v1} can override both paths per provider.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleClient {

    /** The only protocol value accepted by this client. */
    public static final String PROTOCOL = "openai-compatible";

    public static final String DEFAULT_CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    public static final String DEFAULT_MODELS_PATH = "/v1/models";
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int MIN_TIMEOUT_SECONDS = 3;
    public static final int MAX_TIMEOUT_SECONDS = 180;

    private static final int MAX_DISCOVERED_MODELS = 200;
    private static final int MAX_MESSAGE_LENGTH = 400;
    private static final String PROBE_PROMPT = "ping";
    private static final int PROBE_MAX_TOKENS = 8;

    private final ObjectMapper objectMapper;

    /**
     * Calls the model listing endpoint to verify reachability, authentication, and to discover models.
     */
    public ProviderProbeTO listModels(ModelEndpointTO endpoint) {
        String url = joinUrl(endpoint.baseUrl(), pathOr(endpoint.modelsPath(), DEFAULT_MODELS_PATH));
        long startNanos = System.nanoTime();
        try {
            ResponseEntity<String> response = client(endpoint)
                    .get()
                    .uri(url)
                    .headers(headers -> applyAuth(headers, endpoint))
                    .retrieve()
                    .toEntity(String.class);
            long latencyMs = elapsedMillis(startNanos);
            List<String> models = parseModelIds(response.getBody());
            log.atInfo()
                    .addKeyValue("providerId", endpoint.providerId())
                    .addKeyValue("httpStatus", response.getStatusCode().value())
                    .addKeyValue("modelCount", models.size())
                    .addKeyValue("latencyMs", latencyMs)
                    .log("Model provider listing probe succeeded");
            return ProviderProbeTO.success(
                    response.getStatusCode().value(),
                    latencyMs,
                    models,
                    "模型列表获取成功，共发现 " + models.size() + " 个模型");
        } catch (RestClientResponseException exception) {
            return httpFailure(endpoint, exception.getStatusCode().value(), exception.getResponseBodyAsString(),
                    elapsedMillis(startNanos), "模型列表");
        } catch (ResourceAccessException exception) {
            return networkFailure(endpoint, exception, elapsedMillis(startNanos));
        }
    }

    /**
     * Sends a minimal chat completion to verify that a specific model name is usable.
     */
    public ProviderProbeTO probeChat(ModelEndpointTO endpoint, String modelName) {
        if (!StringUtils.hasText(modelName)) {
            return ProviderProbeTO.failure(
                    ProviderProbeTO.STATUS_CLIENT_ERROR,
                    null,
                    0L,
                    "未指定要验证的模型名称");
        }
        String url = joinUrl(endpoint.baseUrl(),
                pathOr(endpoint.chatCompletionsPath(), DEFAULT_CHAT_COMPLETIONS_PATH));
        long startNanos = System.nanoTime();
        try {
            ResponseEntity<String> response = client(endpoint)
                    .post()
                    .uri(url)
                    .headers(headers -> applyAuth(headers, endpoint))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(chatProbeBody(modelName))
                    .retrieve()
                    .toEntity(String.class);
            long latencyMs = elapsedMillis(startNanos);
            log.atInfo()
                    .addKeyValue("providerId", endpoint.providerId())
                    .addKeyValue("modelName", modelName)
                    .addKeyValue("httpStatus", response.getStatusCode().value())
                    .addKeyValue("latencyMs", latencyMs)
                    .log("Model provider chat probe succeeded");
            return ProviderProbeTO.success(
                    response.getStatusCode().value(),
                    latencyMs,
                    List.of(modelName),
                    "模型 " + modelName + " 对话验证通过，耗时 " + latencyMs + " ms");
        } catch (RestClientResponseException exception) {
            return httpFailure(endpoint, exception.getStatusCode().value(), exception.getResponseBodyAsString(),
                    elapsedMillis(startNanos), "模型 " + modelName + " 对话验证");
        } catch (ResourceAccessException exception) {
            return networkFailure(endpoint, exception, elapsedMillis(startNanos));
        }
    }

    // ---------- protocol helpers ----------

    private Map<String, Object> chatProbeBody(String modelName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("messages", List.of(Map.of("role", "user", "content", PROBE_PROMPT)));
        body.put("max_tokens", PROBE_MAX_TOKENS);
        body.put("temperature", 0);
        body.put("stream", false);
        return body;
    }

    private List<String> parseModelIds(String body) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        JsonNode array;
        try {
            JsonNode root = objectMapper.readTree(body);
            array = root.isArray() ? root : root.path("data");
        } catch (IOException exception) {
            return List.of();
        }
        if (!array.isArray()) {
            return List.of();
        }
        List<String> modelIds = new ArrayList<>();
        for (JsonNode node : array) {
            String modelId = node.isTextual() ? node.asText() : node.path("id").asText(null);
            if (StringUtils.hasText(modelId)) {
                modelIds.add(modelId);
            }
            if (modelIds.size() >= MAX_DISCOVERED_MODELS) {
                break;
            }
        }
        return List.copyOf(modelIds);
    }

    private void applyAuth(HttpHeaders headers, ModelEndpointTO endpoint) {
        if (endpoint.hasApiKey()) {
            headers.setBearerAuth(endpoint.apiKey());
        }
    }

    private RestClient client(ModelEndpointTO endpoint) {
        int timeoutSeconds = clampTimeout(endpoint.timeoutSeconds());
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private ProviderProbeTO httpFailure(ModelEndpointTO endpoint, int httpStatus, String responseBody,
                                        long latencyMs, String action) {
        String excerpt = sanitize(responseBody);
        String status;
        String message;
        if (httpStatus == 401 || httpStatus == 403) {
            status = ProviderProbeTO.STATUS_AUTH_FAILED;
            message = action + "失败：鉴权未通过（HTTP " + httpStatus + "），请检查 API 密钥是否正确或已过期";
        } else if (httpStatus == 404) {
            status = ProviderProbeTO.STATUS_NOT_FOUND;
            message = action + "失败：端点不存在（HTTP 404），请检查接口地址与请求路径是否匹配该供应商";
        } else if (httpStatus == 429) {
            status = ProviderProbeTO.STATUS_RATE_LIMITED;
            message = action + "失败：请求过于频繁或额度不足（HTTP 429）";
        } else if (httpStatus >= 500) {
            status = ProviderProbeTO.STATUS_SERVER_ERROR;
            message = action + "失败：服务端错误（HTTP " + httpStatus + "）";
        } else {
            status = ProviderProbeTO.STATUS_CLIENT_ERROR;
            message = action + "失败：HTTP " + httpStatus;
        }
        if (StringUtils.hasText(excerpt)) {
            message = message + "，响应摘要：" + excerpt;
        }
        log.atWarn()
                .addKeyValue("providerId", endpoint.providerId())
                .addKeyValue("httpStatus", httpStatus)
                .addKeyValue("status", status)
                .addKeyValue("latencyMs", latencyMs)
                .log("Model provider probe returned an error status");
        return ProviderProbeTO.failure(status, httpStatus, latencyMs, message);
    }

    private ProviderProbeTO networkFailure(ModelEndpointTO endpoint, ResourceAccessException exception,
                                           long latencyMs) {
        String status;
        String message;
        if (hasCause(exception, HttpTimeoutException.class) || hasCause(exception, SocketTimeoutException.class)) {
            status = ProviderProbeTO.STATUS_TIMEOUT;
            message = "连接超时（" + clampTimeout(endpoint.timeoutSeconds()) + " 秒无响应），请检查网络或调大超时时间";
        } else if (hasCause(exception, UnknownHostException.class)) {
            status = ProviderProbeTO.STATUS_UNREACHABLE;
            message = "无法解析主机名，请检查接口地址是否正确";
        } else if (hasCause(exception, ConnectException.class)) {
            status = ProviderProbeTO.STATUS_UNREACHABLE;
            message = "连接被拒绝，请确认服务已启动且地址端口正确";
        } else {
            status = ProviderProbeTO.STATUS_UNREACHABLE;
            message = "网络不可达：" + sanitize(exception.getMessage());
        }
        // The key is never part of the message; only the endpoint and status are logged.
        log.atWarn()
                .setCause(exception)
                .addKeyValue("providerId", endpoint.providerId())
                .addKeyValue("baseUrl", endpoint.baseUrl())
                .addKeyValue("status", status)
                .addKeyValue("latencyMs", latencyMs)
                .log("Model provider probe could not reach the endpoint");
        return ProviderProbeTO.failure(status, null, latencyMs, message);
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Truncates an upstream message or body so that error text stays usable without echoing payloads.
     */
    private static String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String singleLine = value.replaceAll("\\s+", " ").trim();
        if (singleLine.length() <= MAX_MESSAGE_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, MAX_MESSAGE_LENGTH) + "...";
    }

    private static String joinUrl(String baseUrl, String path) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return normalizedBase + normalizedPath;
    }

    private static String pathOr(String path, String fallback) {
        return StringUtils.hasText(path) ? path.trim() : fallback;
    }

    public static int clampTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.min(Math.max(timeoutSeconds, MIN_TIMEOUT_SECONDS), MAX_TIMEOUT_SECONDS);
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
