package com.rcdis.agent.vo;

/**
 * Client-facing view of an uploaded Agent attachment (never exposes the raw stored path).
 */
public record AgentAttachmentVO(
        Long id,
        String conversationId,
        String originalName,
        String url,
        String mime,
        String ext,
        Long sizeBytes,
        String kind,
        String extractStatus,
        Integer extractedLength
) {
}
