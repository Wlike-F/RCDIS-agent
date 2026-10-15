package com.rcdis.agent.vo;

import java.time.OffsetDateTime;

/**
 * Admin-facing view of one evaluation candidate, including the review and reproduction trail that
 * decides whether it may be promoted into the version-controlled dataset.
 */
public record EvalCandidateVO(
        Long id,
        String fingerprint,
        String source,
        String failureType,
        String categoryGuess,
        String riskLevel,
        String inputText,
        String observedToolsJson,
        String evidenceJson,
        String sourceConversationId,
        String sourceTraceIds,
        Integer occurrences,
        OffsetDateTime firstSeenAt,
        OffsetDateTime lastSeenAt,
        String status,
        String expectedBehavior,
        String confirmedBy,
        OffsetDateTime confirmedAt,
        String reproduceEnv,
        OffsetDateTime reproducedAt,
        String reproduceRunId,
        String promoteNote,
        String promotedCaseId
) {
}
