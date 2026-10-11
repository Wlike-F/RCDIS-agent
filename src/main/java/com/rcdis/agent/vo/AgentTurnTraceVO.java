package com.rcdis.agent.vo;

import java.time.OffsetDateTime;
import java.util.List;

import com.rcdis.agent.to.ToolCallTraceTO;

/**
 * Developer-facing view of one Agent turn trace.
 *
 * <p>{@code haltReason}, {@code haltCount} and {@code frozenTools} surface the runtime circuit
 * breaker outcome; every other field describes the turn's cost and latency. The workbench trace
 * tab reads these to answer "did the model spin on a failing tool this turn, and which tools
 * were frozen as a result".</p>
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
        OffsetDateTime createdAt,
        String haltReason,
        Integer haltCount,
        List<String> frozenTools
) {
}
