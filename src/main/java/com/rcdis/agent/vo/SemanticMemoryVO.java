package com.rcdis.agent.vo;

import java.time.OffsetDateTime;

/**
 * One cross-session semantic memory row for the memory panel.
 */
public record SemanticMemoryVO(
        Long id,
        String scope,
        String factType,
        String content,
        String sourceConversationId,
        Integer sourceSeq,
        Integer hitCount,
        OffsetDateTime lastHitAt,
        OffsetDateTime createdAt
) {
}
