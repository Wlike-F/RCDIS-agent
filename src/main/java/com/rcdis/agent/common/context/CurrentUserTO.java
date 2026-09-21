package com.rcdis.agent.common.context;

import java.util.Set;

import org.springframework.util.StringUtils;

/**
 * The verified identity for the current request or workflow.
 *
 * <p>{@code roles} carries the role codes (ADMIN/APPROVER/RESEARCHER) resolved from a verified JWT,
 * or from an approver binding on a Feishu callback thread. Identity must never be taken from
 * client-supplied headers.</p>
 */
public record CurrentUserTO(
        String userId,
        String username,
        String tenantId,
        String conversationId,
        Set<String> roles
) {

    public CurrentUserTO {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public static CurrentUserTO anonymous() {
        return new CurrentUserTO("anonymous", "anonymous", "default", null, Set.of());
    }

    /**
     * Builds a context from already-resolved identity values, normalizing blanks so that downstream
     * audit fields stay non-null.
     */
    public static CurrentUserTO of(
            String userId,
            String username,
            String tenantId,
            String conversationId,
            Set<String> roles
    ) {
        String resolvedUserId = resolveText(userId, "anonymous");
        String resolvedUsername = resolveText(username, resolvedUserId);
        String resolvedTenantId = resolveText(tenantId, "default");
        String resolvedConversationId = resolveNullableText(conversationId);
        return new CurrentUserTO(
                resolvedUserId,
                resolvedUsername,
                resolvedTenantId,
                resolvedConversationId,
                roles == null ? Set.of() : roles);
    }

    public boolean hasRole(String role) {
        return role != null && roles.contains(role);
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
