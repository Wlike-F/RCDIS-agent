package com.rcdis.agent.vo;

/**
 * One member of the approval group chat, offered to the administrator for binding.
 *
 * <p>{@code openId} is returned unmasked here because it is the required input of the bind
 * operation. This endpoint should be restricted to administrators once method level authorization
 * is enabled; member names are personal data and are never written to application logs.</p>
 */
public record FeishuChatMemberVO(
        String openId,
        String name,
        boolean bound,
        Long approverId,
        String boundRole
) {
}
