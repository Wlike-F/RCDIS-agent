package com.rcdis.agent.vo;

import java.time.OffsetDateTime;

import com.rcdis.agent.entity.NotificationTemplateEntity;

public record FeishuNotificationTemplateVO(
        Long id,
        String templateCode,
        String templateName,
        String scene,
        String description,
        String messageType,
        String content,
        boolean builtin,
        String status,
        Integer version,
        OffsetDateTime updatedAt
) {

    public static FeishuNotificationTemplateVO fromEntity(NotificationTemplateEntity entity) {
        return new FeishuNotificationTemplateVO(
                entity.getId(),
                entity.getTemplateCode(),
                entity.getTemplateName(),
                entity.getScene(),
                entity.getDescription(),
                entity.getMessageType(),
                entity.getContent(),
                Integer.valueOf(1).equals(entity.getBuiltin()),
                entity.getStatus(),
                entity.getVersion(),
                entity.getUpdatedAt());
    }
}
