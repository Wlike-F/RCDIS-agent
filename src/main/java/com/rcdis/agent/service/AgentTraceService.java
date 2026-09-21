package com.rcdis.agent.service;

import java.util.List;

import com.rcdis.agent.agent.TurnTraceCollector;
import com.rcdis.agent.vo.AgentTurnTraceVO;

/**
 * Persists and reads per-turn Agent observability traces.
 */
public interface AgentTraceService {

    /**
     * Flushes a finished turn collector into {@code agent_turn_trace}. Failures here must never
     * break the chat turn itself, so implementations swallow persistence errors after logging.
     */
    void record(TurnTraceCollector collector,
                String conversationId,
                String providerCode,
                String modelName,
                String status,
                String errorMessage);

    /** Traces of one conversation, oldest first, for the workbench trace tab. */
    List<AgentTurnTraceVO> listByConversation(String conversationId);
}
