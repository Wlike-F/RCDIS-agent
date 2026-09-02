package com.rcdis.agent.vo;

import java.time.OffsetDateTime;

import com.rcdis.agent.entity.NotificationOutboxEntity;

public record NotificationOutboxVO(
        Long id,
        String channel,
        String target,
        String messageType,
        String payload,
        String status,
        String idempotencyKey,
        String errorMessage,
        OffsetDateTime sentAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static NotificationOutboxVO fromEntity(NotificationOutboxEntity entity) {
        return new NotificationOutboxVO(
                entity.getId(),
                entity.getChannel(),
                entity.getTarget(),
                entity.getMessageType(),
                entity.getPayload(),
                entity.getStatus(),
                entity.getIdempotencyKey(),
                entity.getErrorMessage(),
                entity.getSentAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
