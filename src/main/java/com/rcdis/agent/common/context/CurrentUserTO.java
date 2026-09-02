package com.rcdis.agent.common.context;

import org.springframework.util.StringUtils;

public record CurrentUserTO(
        String userId,
        String username,
        String tenantId,
        String conversationId
) {

    public static CurrentUserTO anonymous() {
        return new CurrentUserTO("anonymous", "anonymous", "default", null);
    }

    public static CurrentUserTO fromHeaders(
            String userId,
            String username,
            String tenantId,
            String conversationId
    ) {
        String resolvedUserId = resolveText(userId, "anonymous");
        String resolvedUsername = resolveText(username, resolvedUserId);
        String resolvedTenantId = resolveText(tenantId, "default");
        String resolvedConversationId = resolveNullableText(conversationId);
        return new CurrentUserTO(resolvedUserId, resolvedUsername, resolvedTenantId, resolvedConversationId);
    }

    private static String resolveText(String value, String replacement) {
        if (StringUtils.hasText(value)) {
            return value.trim();
        }
        return replacement;
    }

    private static String resolveNullableText(String value) {
        if (StringUtils.hasText(value)) {
            return value.trim();
        }
        return null;
    }
}
