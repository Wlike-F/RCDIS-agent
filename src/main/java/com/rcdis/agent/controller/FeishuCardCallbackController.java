package com.rcdis.agent.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.infrastructure.feishu.FeishuCallbackCrypto;
import com.rcdis.agent.service.FeishuCardActionService;
import com.rcdis.agent.to.CardActionOutcomeTO;
import com.rcdis.agent.to.CardActionRequestTO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * HTTP transport for Feishu card callbacks, the production counterpart of the WebSocket listener.
 *
 * <p>Both transports normalize their payload into {@link CardActionRequestTO} and delegate to the
 * same {@link FeishuCardActionService}, so authorization, idempotency and execution exist once.</p>
 *
 * <p>This endpoint authenticates the caller itself rather than relying on the application's JWT
 * filter: it verifies {@code X-Lark-Signature} and the Verification Token. When no Encrypt Key is
 * configured Feishu cannot sign the request, so the endpoint refuses the call instead of executing an
 * unauthenticated financial state change.</p>
 *
 * <p>Feishu requires an answer within 3 seconds and rejects redirect statuses, therefore business
 * rejections are returned as HTTP 200 with an error toast rather than as 4xx or 5xx.</p>
 */
@Slf4j
@Tag(name = "Feishu Card Callback")
@RestController
@RequestMapping("/api/feishu")
@RequiredArgsConstructor
public class FeishuCardCallbackController {

    private static final String CARD_TYPE_RAW = "raw";
    private static final String TYPE_URL_VERIFICATION = "url_verification";
    private static final String EVENT_TYPE_CARD_ACTION = "card.action.trigger";

    private final FeishuCardActionService feishuCardActionService;
    private final FeishuCallbackCrypto feishuCallbackCrypto;
    private final ObjectMapper objectMapper;

    @Operation(summary = "Receive a Feishu card action callback")
    @PostMapping("/card-callback")
    public ResponseEntity<Map<String, Object>> onCardCallback(
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
            @RequestBody String rawBody) {

        JsonNode payload;
        String plainBody;
        try {
            JsonNode envelope = objectMapper.readTree(rawBody == null ? "" : rawBody);
            plainBody = plainBody(envelope);
            payload = objectMapper.readTree(plainBody);
        } catch (RuntimeException | java.io.IOException exception) {
            log.atWarn().setCause(exception).log("Rejected an unparsable Feishu card callback body");
            return rejected("callback body could not be parsed");
        }

        try {
            if (TYPE_URL_VERIFICATION.equals(payload.path("type").asText(null))) {
                return handleUrlVerification(payload);
            }
            if (!isAuthenticated(payload, timestamp, nonce, signature, rawBody)) {
                return rejected("callback authentication failed");
            }

            JsonNode header = payload.path("header");
            if (!EVENT_TYPE_CARD_ACTION.equals(header.path("event_type").asText(null))) {
                // Acknowledge callbacks this application does not handle so Feishu stops retrying.
                log.atInfo()
                        .addKeyValue("eventType", header.path("event_type").asText(null))
                        .log("Ignored a Feishu callback of an unhandled type");
                return ResponseEntity.ok(new LinkedHashMap<>());
            }

            CardActionOutcomeTO outcome = feishuCardActionService.handleCardAction(toRequest(payload, plainBody));
            return ResponseEntity.ok(toResponseBody(outcome));
        } catch (RuntimeException exception) {
            // Answer 200 with an error toast: a 5xx would make Feishu retry and could re-run a
            // decision that already committed before the failure.
            log.atError()
                    .setCause(exception)
                    .addKeyValue("eventId", payload.path("header").path("event_id").asText(null))
                    .log("Feishu card callback handling failed");
            return ResponseEntity.ok(toResponseBody(CardActionOutcomeTO.toast(
                    CardActionOutcomeTO.TOAST_ERROR,
                    "处理失败，请到报销中心查看该单据状态后再操作")));
        }
    }

    /**
     * Answers the challenge Feishu sends once when the callback address is saved.
     */
    private ResponseEntity<Map<String, Object>> handleUrlVerification(JsonNode payload) {
        if (!feishuCallbackCrypto.verifyToken(payload.path("token").asText(null))) {
            return rejected("url verification token mismatch");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("challenge", payload.path("challenge").asText(""));
        log.atInfo().log("Answered a Feishu callback URL verification challenge");
        return ResponseEntity.ok(body);
    }

    private boolean isAuthenticated(JsonNode payload, String timestamp, String nonce, String signature,
                                    String rawBody) {
        if (!feishuCallbackCrypto.isSignatureVerifiable()) {
            log.atWarn().log("Refusing a Feishu card callback because rcdis.feishu.encrypt-key is not configured, "
                    + "so the request cannot be authenticated");
            return false;
        }
        if (!feishuCallbackCrypto.verifySignature(timestamp, nonce, rawBody, signature)) {
            log.atWarn().log("Rejected a Feishu card callback with an invalid X-Lark-Signature");
            return false;
        }
        if (!feishuCallbackCrypto.verifyToken(payload.path("header").path("token").asText(null))) {
            log.atWarn().log("Rejected a Feishu card callback with an invalid verification token");
            return false;
        }
        return true;
    }

    /**
     * Decrypts the {@code {"encrypt":"..."}} envelope when an Encrypt Key is configured.
     */
    private String plainBody(JsonNode envelope) {
        JsonNode encrypt = envelope.path("encrypt");
        if (encrypt.isTextual() && StringUtils.hasText(encrypt.asText())) {
            return feishuCallbackCrypto.decrypt(encrypt.asText());
        }
        return envelope.toString();
    }

    private CardActionRequestTO toRequest(JsonNode payload, String plainBody) {
        JsonNode header = payload.path("header");
        JsonNode event = payload.path("event");
        JsonNode operator = event.path("operator");
        JsonNode action = event.path("action");
        JsonNode context = event.path("context");
        return new CardActionRequestTO(
                header.path("event_id").asText(null),
                header.path("event_type").asText(null),
                header.path("token").asText(null),
                operator.path("open_id").asText(null),
                operator.path("user_id").asText(null),
                operator.path("tenant_key").asText(null),
                context.path("open_chat_id").asText(null),
                context.path("open_message_id").asText(null),
                action.path("tag").asText(null),
                toMap(action.path("value")),
                toMap(action.path("form_value")),
                // The decrypted body is stored, not the ciphertext, so the record stays readable.
                plainBody);
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        try {
            return objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IllegalArgumentException exception) {
            log.atWarn().setCause(exception).log("Failed to read a Feishu card action value object");
            return Map.of();
        }
    }

    private Map<String, Object> toResponseBody(CardActionOutcomeTO outcome) {
        Map<String, Object> toast = new LinkedHashMap<>();
        toast.put("type", outcome.toastType());
        toast.put("content", outcome.toastContent());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("toast", toast);
        if (outcome.card() != null) {
            // type=raw means the card is supplied as JSON; it must stay card JSON 2.0 because Feishu
            // error 200830 rejects replacing a 2.0 card with 1.0 content.
            body.put("card", Map.of("type", CARD_TYPE_RAW, "data", outcome.card()));
        }
        return body;
    }

    /**
     * Deliberately vague: the response must not tell a caller which part of the authentication failed.
     */
    private ResponseEntity<Map<String, Object>> rejected(String reasonForLog) {
        log.atWarn()
                .addKeyValue("reason", reasonForLog)
                .log("Rejected a Feishu card callback");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", Integer.valueOf(HttpStatus.FORBIDDEN.value()));
        body.put("msg", "rejected");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
