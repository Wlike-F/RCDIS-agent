package com.rcdis.agent.vo;

import java.time.OffsetDateTime;

import com.rcdis.agent.entity.FeishuApproverEntity;

/**
 * Approver binding as shown in the administration page.
 *
 * <p>{@code openId} is masked because the page only needs to display and delete by {@code id}.
 * The unmasked value is exposed by the chat member listing, where it is a required operation input.</p>
 */
public record FeishuApproverVO(
        Long id,
        String openId,
        String userId,
        String userName,
        String role,
        String status,
        String remark,
        Integer version,
        OffsetDateTime updatedAt
) {

    private static final int VISIBLE_HEAD = 6;
    private static final int VISIBLE_TAIL = 4;

    public static FeishuApproverVO fromEntity(FeishuApproverEntity entity) {
        return new FeishuApproverVO(
                entity.getId(),
                maskOpenId(entity.getOpenId()),
                entity.getUserId(),
                entity.getUserName(),
                entity.getRole(),
                entity.getStatus(),
                entity.getRemark(),
                entity.getVersion(),
                entity.getUpdatedAt());
    }

    private static String maskOpenId(String openId) {
        if (openId == null || openId.length() <= VISIBLE_HEAD + VISIBLE_TAIL) {
            return openId == null ? null : "****";
        }
        return openId.substring(0, VISIBLE_HEAD)
                + "****"
                + openId.substring(openId.length() - VISIBLE_TAIL);
    }
}
