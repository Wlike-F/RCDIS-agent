package com.rcdis.agent.vo;

import java.time.OffsetDateTime;

/**
 * Sidebar entry for one conversation. Messages are fetched separately on demand so opening the
 * workbench stays cheap even with long histories.
 */
public record ChatSessionVO(
        String conversationId,
        String title,
        String providerCode,
        Integer messageCount,
        OffsetDateTime lastMessageAt,
        OffsetDateTime createdAt
) {
}
