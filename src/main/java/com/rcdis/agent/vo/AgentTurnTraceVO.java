package com.rcdis.agent.vo;

import java.time.OffsetDateTime;
import java.util.List;

import com.rcdis.agent.to.ToolCallTraceTO;

/**
 * Developer-facing view of one Agent turn trace.
 */
public record AgentTurnTraceVO(
        Long id,
        String conversationId,
        Integer turnSeq,
        String providerCode,
        String modelName,
        String status,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long firstTokenMs,
        Long totalMs,
        List<ToolCallTraceTO> toolCalls,
        String errorMessage,
        OffsetDateTime createdAt
) {
}
