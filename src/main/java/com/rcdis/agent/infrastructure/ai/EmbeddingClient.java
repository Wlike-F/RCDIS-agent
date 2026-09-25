package com.rcdis.agent.infrastructure.ai;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.to.ModelEndpointTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Minimal OpenAI-compatible embeddings client, mirroring {@link OpenAiCompatibleClient}'s transport
 * so it works against the same providers (DashScope compatible mode, Ollama {@code /v1}, vLLM, ...).
 *
 * <p>Contract: {@code POST {baseUrl}{embeddingsPath}} with {@code {"model","input"}} returning
 * {@code {"data":[{"embedding":[...]}]}}. Only used on the semantic-retrieval path, which is gated
 * off by default and never exercised in H2 tests, so any failure here is surfaced to the caller to
 * degrade to keyword / newest-N rather than breaking the turn.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingClient {

    public static final String DEFAULT_EMBEDDINGS_PATH = "/v1/embeddings";
    private static final int MAX_MESSAGE_LENGTH = 300;

    private final ObjectMapper objectMapper;

    /**
     * Embeds a single text against the given provider endpoint.
     *
     * @return the embedding vector
     * @throws EmbeddingException when the call fails or the response is malformed
     */
    public List<Double> embed(ModelEndpointTO endpoint, String embeddingsPath, String model, String text) {
        if (endpoint == null || !StringUtils.hasText(model) || !StringUtils.hasText(text)) {
            throw new EmbeddingException("embedding request is missing endpoint, model, or text");
        }
        String url = joinUrl(endpoint.baseUrl(),
                StringUtils.hasText(embeddingsPath) ? embeddingsPath : DEFAULT_EMBEDDINGS_PATH);
        long startNanos = System.nanoTime();
        try {
            Map<String, Object> body = Map.of("model", model, "input", text);
            ResponseEntity<String> response = client(endpoint)
                    .post()
                    .uri(url)
                    .headers(headers -> {
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        if (endpoint.hasApiKey()) {
                            headers.setBearerAuth(endpoint.apiKey());
                        }
                    })
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);
            List<Double> vector = parseEmbedding(response.getBody());
            log.atDebug()
                    .addKeyValue("providerId", endpoint.providerId())
                    .addKeyValue("model", model)
                    .addKeyValue("dimension", vector.size())
                    .addKeyValue("latencyMs", elapsedMillis(startNanos))
                    .log("Generated semantic-memory embedding");
            return vector;
        } catch (RuntimeException exception) {
            throw new EmbeddingException(
                    "embedding call failed: " + sanitize(exception.getMessage()), exception);
        }
    }

    private List<Double> parseEmbedding(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            throw new EmbeddingException("embedding response was empty");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (IOException exception) {
            throw new EmbeddingException("embedding response was not valid JSON", exception);
        }
        JsonNode embedding = root.path("data").path(0).path("embedding");
        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new EmbeddingException("embedding response missing data[0].embedding");
        }
        List<Double> vector = new ArrayList<>(embedding.size());
        for (JsonNode value : embedding) {
            vector.add(value.asDouble());
        }
        return vector;
    }

    private RestClient client(ModelEndpointTO endpoint) {
        int timeoutSeconds = OpenAiCompatibleClient.clampTimeout(endpoint.timeoutSeconds());
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    private static String joinUrl(String baseUrl, String path) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return normalizedBase + normalizedPath;
    }

    private static String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String singleLine = value.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= MAX_MESSAGE_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_MESSAGE_LENGTH) + "...";
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** Signals an embeddings failure so callers can degrade instead of breaking the turn. */
    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) {
            super(message);
        }

        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
