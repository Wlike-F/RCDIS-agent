package com.rcdis.agent.infrastructure.feishu;

/**
 * Truncates upstream Feishu response bodies before they reach exception messages or logs.
 *
 * <p>Keeps error text usable for debugging without echoing arbitrarily large payloads.</p>
 */
public final class FeishuResponseSanitizer {

    private static final int MAX_LENGTH = 500;

    private FeishuResponseSanitizer() {
        throw new UnsupportedOperationException("FeishuResponseSanitizer cannot be instantiated");
    }

    public static String sanitize(String responseBody) {
        if (responseBody == null) {
            return "";
        }
        String trimmed = responseBody.trim();
        if (trimmed.length() <= MAX_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_LENGTH);
    }
}
