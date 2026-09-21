package com.rcdis.agent.vo;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Client-facing view of a conversation's compression state, for the trace-tab memory panel.
 */
public record AgentMemoryVO(
        String conversationId,
        String rollingSummary,
        List<SummaryFactVO> facts,
        Integer summaryUptoSeq,
        int windowMessages,
        int windowTokens,
        Integer compressCount,
        OffsetDateTime lastCompressedAt,
        long totalMessages
) {
    public record SummaryFactVO(
            String type,
            String text,
            Integer turnSeq,
            String key
    ) {
    }
}
