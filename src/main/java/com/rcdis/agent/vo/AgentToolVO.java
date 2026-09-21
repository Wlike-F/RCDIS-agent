package com.rcdis.agent.vo;

import java.util.List;

/**
 * Developer-facing description of one registered Agent tool.
 *
 * <p>{@code category} is {@code READ} for query-only tools and {@code WRITE} for tools that mutate
 * data; write tools always require an explicit confirmation step before executing.</p>
 */
public record AgentToolVO(
        String name,
        String description,
        String category,
        boolean requiresConfirmation,
        String status,
        List<AgentToolParamVO> params
) {
}
