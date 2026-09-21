package com.rcdis.agent.to;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

/**
 * What the model sees for a turn: the compressed prefix (summary + facts) plus the recent raw window.
 */
public record ContextSnapshotTO(
        String rollingSummary,
        String summaryFactsJson,
        Integer summaryUptoSeq,
        List<Message> recentMessages
) {
}
