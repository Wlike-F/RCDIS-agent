package com.rcdis.agent.vo;

public record FeishuConfigStatusVO(
        boolean enabled,
        String clientType,
        String channel,
        String status,
        String message,
        String openApiBaseUrl,
        boolean appIdConfigured,
        boolean appSecretConfigured,
        boolean defaultReceiveIdConfigured,
        String defaultReceiveIdType,
        String maskedDefaultReceiveId,
        boolean webhookConfigured,
        boolean signingSecretConfigured,
        int maxAttempts
) {
}
