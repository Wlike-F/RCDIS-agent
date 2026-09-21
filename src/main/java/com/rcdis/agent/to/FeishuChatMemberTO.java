package com.rcdis.agent.to;

/**
 * One member of a Feishu group chat, used to let an administrator pick approvers.
 */
public record FeishuChatMemberTO(
        String openId,
        String name,
        String tenantKey
) {
}
