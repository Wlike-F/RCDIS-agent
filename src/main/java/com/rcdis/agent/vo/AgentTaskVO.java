package com.rcdis.agent.vo;

import java.time.OffsetDateTime;
import java.util.List;

/** Read model for a Plan-and-Execute task and its ordered steps. */
public record AgentTaskVO(
        Long id,
        String conversationId,
        String taskType,
        String title,
        String status,
        Integer totalSteps,
        Integer completedSteps,
        Integer failedSteps,
        String errorMessage,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        List<StepVO> steps
) {

    public record StepVO(
            Long id,
            Integer stepNo,
            String action,
            String targetType,
            String targetId,
            String status,
            String outputJson,
            String errorMessage,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt
    ) {
    }
}
