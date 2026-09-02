package com.rcdis.agent.dto;

import java.time.OffsetDateTime;

public record FeishuMessageResponse(
        Long notificationId,
        String idempotencyKey,
        String messageType,
        String target,
        String status,
        boolean duplicate,
        OffsetDateTime sentAt
) {
}
