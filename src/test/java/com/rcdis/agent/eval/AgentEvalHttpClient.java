package com.rcdis.agent.eval;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Minimal black-box client for the live Agent SSE API. */
public final class AgentEvalHttpClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration timeout;
    private final String accessToken;

    public AgentEvalHttpClient(
            ObjectMapper objectMapper,
            String baseUrl,
            Duration timeout,
            String username,
            String password
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.accessToken = login(username, password);
    }

    public AgentEvalObservation execute(String providerId, String input) {
        String conversationId = "eval-" + UUID.randomUUID();
        long started = System.nanoTime();
        try {
            String body = objectMapper.writeValueAsString(new ChatPayload(conversationId, providerId, input, List.of()));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat/stream"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return failed(providerId, conversationId, started,
                        "HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
            }
            return parseEvents(providerId, conversationId, response.body(), started);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failed(providerId, conversationId, started, "Interrupted while waiting for Agent response");
        } catch (IOException | RuntimeException exception) {
            return failed(providerId, conversationId, started,
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    private AgentEvalObservation parseEvents(
            String providerId,
            String conversationId,
            String raw,
            long started
    ) throws IOException {
        Set<String> tools = new LinkedHashSet<>();
        Map<String, Integer> toolCounts = new HashMap<>();
        boolean confirmation = false;
        int confirmationEvents = 0;
        String confirmationId = null;
        boolean needsInput = false;
        String responseText = "";
        String modelName = null;
        String error = null;
        String normalized = raw == null ? "" : raw.replace("\r\n", "\n");
        for (String block : normalized.split("\n\n")) {
            SseEvent event = parseBlock(block);
            if (event == null || event.data().isBlank()) {
                continue;
            }
            JsonNode payload = objectMapper.readTree(event.data());
            switch (event.name()) {
                case "tool_start" -> {
                    String toolName = payload.path("toolName").asText(null);
                    if (toolName != null) {
                        tools.add(toolName);
                        toolCounts.merge(toolName, 1, Integer::sum);
                    }
                }
                case "requires_confirmation" -> {
                    confirmation = true;
                    confirmationEvents++;
                    if (confirmationId == null) {
                        confirmationId = payload.path("confirmationId").asText(null);
                    }
                }
                case "agent_needs_input" -> needsInput = true;
                case "done" -> {
                    responseText = payload.path("content").asText("");
                    modelName = payload.path("modelName").asText(null);
                }
                case "error" -> error = payload.path("message").asText("Unknown Agent error");
                default -> {
                    // Other events do not affect deterministic assertions.
                }
            }
        }
        return new AgentEvalObservation(providerId, modelName, conversationId, responseText,
                new ArrayList<>(tools), confirmation, error, elapsedMs(started),
                needsInput, toolCounts, confirmationEvents, confirmationId);
    }

    /**
     * Resolves a pending proposal through the public confirm endpoint.
     *
     * <p>Used only by cases that opt into {@code confirmAndReplay}; calling it twice with the same
     * id is what proves the execution guard, because a second call must report "not executed again".</p>
     */
    public ConfirmOutcome confirm(String conversationId, String confirmationId, boolean approved) {
        try {
            String body = objectMapper.writeValueAsString(
                    new ConfirmPayload(conversationId, confirmationId, approved));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat/confirm"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ConfirmOutcome(null, null, "HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
            }
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            return new ConfirmOutcome(data.path("status").asText(null),
                    data.path("executed").asBoolean(false), null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ConfirmOutcome(null, null, "Interrupted while confirming");
        } catch (IOException | RuntimeException exception) {
            return new ConfirmOutcome(null, null, String.valueOf(exception.getMessage()));
        }
    }

    /** Reads back a reimbursement's business status by keyword; null when nothing matches. */
    public String reimbursementStatus(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        try {
            String url = baseUrl + "/api/reimbursements?current=1&size=1&keyword="
                    + java.net.URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(timeout)
                            .header("Authorization", "Bearer " + accessToken)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            return objectMapper.readTree(response.body()).path("data").path("records")
                    .path(0).path("status").asText(null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private SseEvent parseBlock(String block) {
        if (block == null || block.isBlank()) {
            return null;
        }
        String eventName = "message";
        List<String> data = new ArrayList<>();
        for (String line : block.split("\n")) {
            if (line.startsWith("event:")) {
                eventName = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                data.add(line.substring("data:".length()).stripLeading());
            }
        }
        return new SseEvent(eventName, String.join("\n", data));
    }

    private String login(String username, String password) {
        try {
            String body = objectMapper.writeValueAsString(new LoginPayload(username, password));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/auth/login"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Agent eval login failed with HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String token = root.path("data").path("accessToken").asText(null);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Agent eval login response did not contain an access token");
            }
            return token;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent eval login was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Agent eval could not connect to " + baseUrl, exception);
        }
    }

    private AgentEvalObservation failed(String providerId, String conversationId, long started, String error) {
        return new AgentEvalObservation(providerId, null, conversationId, "", List.of(), false,
                error, elapsedMs(started));
    }

    private static long elapsedMs(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static String stripTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Agent eval base URL must not be blank");
        }
        return normalized;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record LoginPayload(String username, String password) {
    }

    private record ChatPayload(String conversationId, String providerId, String message, List<Long> attachmentIds) {
    }

    private record ConfirmPayload(String conversationId, String confirmationId, boolean approved) {
    }

    /** Outcome of one confirm call; {@code error} is set only when the HTTP call itself failed. */
    public record ConfirmOutcome(String status, Boolean executed, String error) {
    }

    private record SseEvent(String name, String data) {
    }
}
