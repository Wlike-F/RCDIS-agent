package com.rcdis.agent.vo;

import java.time.OffsetDateTime;

import com.rcdis.agent.entity.AppNotificationEntity;

public record AppNotificationVO(
        Long id,
        String recipient,
        String type,
        String title,
        String content,
        String bizType,
        Long bizId,
        boolean read,
        OffsetDateTime createdAt
) {

    public static AppNotificationVO fromEntity(AppNotificationEntity entity) {
        return new AppNotificationVO(
                entity.getId(),
                entity.getRecipient(),
                entity.getType(),
                entity.getTitle(),
                entity.getContent(),
                entity.getBizType(),
                entity.getBizId(),
                Boolean.TRUE.equals(entity.getIsRead()),
                entity.getCreatedAt());
    }
}
