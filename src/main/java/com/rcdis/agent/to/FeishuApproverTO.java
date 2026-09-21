package com.rcdis.agent.to;

/**
 * An approver identity resolved from a Feishu open_id.
 *
 * <p>Used to rebind the current-user context while executing an approval triggered from a card
 * callback, so that audit entries record the real approver rather than an anonymous actor.</p>
 */
public record FeishuApproverTO(
        String openId,
        String userId,
        String userName,
        String tenantId,
        String role
) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
