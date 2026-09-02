package com.rcdis.agent.dto;

public record ChatResponse(
        String conversationId,
        String content,
        String providerId,
        String modelName
) {
}

