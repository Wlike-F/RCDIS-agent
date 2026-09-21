package com.rcdis.agent.eval;

import java.util.List;

/** Observable result of one live Agent turn. */
public record AgentEvalObservation(
        String providerId,
        String modelName,
        String conversationId,
        String response,
        List<String> tools,
        boolean confirmationRequested,
        String error,
        long durationMs
) {

    public AgentEvalObservation {
        tools = tools == null ? List.of() : List.copyOf(tools);
        response = response == null ? "" : response;
    }
}
