package com.rcdis.agent.vo;

import java.time.OffsetDateTime;

/**
 * One persisted chat turn for history rendering. Internal {@code tool} rows are excluded by the
 * service; this view only carries what the UI renders (user / assistant lines).
 */
public record ChatMessageVO(
        Integer seq,
        String role,
        String content,
        String providerCode,
        String modelName,
        String status,
        String errorMessage,
        OffsetDateTime createdAt
) {
}