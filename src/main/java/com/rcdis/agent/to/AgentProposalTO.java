package com.rcdis.agent.to;

import java.util.Map;

/**
 * A write-tool proposal handed to {@code AgentPendingActionService} for persistence.
 *
 * <p>{@code arguments} must already be fully resolved and executable (project/category codes turned
 * into ids, optimistic-lock version captured) so that the confirm step can replay them verbatim
 * without re-interpreting model output.</p>
 */
public record AgentProposalTO(
        String conversationId,
        String toolName,
        Map<String, Object> arguments,
        String summary,
        String targetType,
        String targetId,
        String beforeSnapshot,
        String afterSnapshot,
        String reason,
        String scope
) {
}
