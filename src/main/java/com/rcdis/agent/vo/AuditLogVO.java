package com.rcdis.agent.vo;

import java.time.OffsetDateTime;

import com.rcdis.agent.entity.AuditLogEntity;

public record AuditLogVO(
        Long id,
        String actor,
        String tenantId,
        String action,
        String targetType,
        String targetId,
        String beforeSnapshot,
        String afterSnapshot,
        String reason,
        String source,
        String conversationId,
        OffsetDateTime createdAt
) {

    public static AuditLogVO fromEntity(AuditLogEntity entity) {
        return new AuditLogVO(
                entity.getId(),
                entity.getActor(),
                entity.getTenantId(),
                entity.getAction(),
                entity.getTargetType(),
                entity.getTargetId(),
                entity.getBeforeSnapshot(),
                entity.getAfterSnapshot(),
                entity.getReason(),
                entity.getSource(),
                entity.getConversationId(),
                entity.getCreatedAt());
    }
}
