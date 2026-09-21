package com.rcdis.agent.common.aop;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;

/** Recursively redacts credentials and sensitive financial fields before audit persistence. */
@Component
@RequiredArgsConstructor
public class AuditDataSanitizer {

    private static final String REDACTED = "[REDACTED]";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "apikey", "accesstoken", "refreshtoken", "token", "secret",
            "clientsecret", "signingsecret", "verificationtoken", "encryptkey", "authorization",
            "credential", "counterpartyaccount", "bankaccount", "accountnumber", "invoiceno",
            "receiptfile", "privatekey", "webhook", "webhookurl");

    private final ObjectMapper objectMapper;

    public JsonNode sanitize(Object value) {
        if (value == null) {
            return null;
        }
        JsonNode copy = objectMapper.valueToTree(value);
        sanitizeNode(copy);
        return copy;
    }

    private void sanitizeNode(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitive(field.getKey())) {
                    objectNode.put(field.getKey(), REDACTED);
                } else {
                    sanitizeNode(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode arrayNode) {
            arrayNode.forEach(this::sanitizeNode);
        }
    }

    private boolean isSensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SENSITIVE_KEYS.contains(normalized);
    }
}
