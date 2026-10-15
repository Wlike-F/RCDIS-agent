package com.rcdis.agent.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * A real-failure candidate for the Agent evaluation set, discovered from persisted turn traces.
 *
 * <p>Candidates are deliberately <em>not</em> part of the version-controlled dataset: a failure that
 * merely happened online is not yet a specification. Each row carries the evidence, an occurrence
 * count, and the review trail (who confirmed the expected behaviour, where it was reproduced) that a
 * case must accumulate before it is promoted into {@code agent-eval/cases.jsonl}.</p>
 */
@Getter
@Setter
@TableName("agent_eval_candidate")
public class AgentEvalCandidateEntity extends BaseEntity {

    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_PROMOTED = "PROMOTED";

    public static final String SOURCE_TURN_TRACE = "agent_turn_trace";
    public static final String SOURCE_PENDING_ACTION = "agent_pending_action";

    /** Dedup key: sha256 of failure type + redacted input + observed tool set. */
    private String fingerprint;
    private String source;
    private String failureType;
    /** Best-effort dataset category guess, purely to help triage; never authoritative. */
    private String categoryGuess;
    private String riskLevel;

    /** Redacted user utterance that triggered the turn. */
    private String inputText;
    private String observedToolsJson;
    /** Structured evidence: trace ids, statuses, halt reason, tool failures, error messages. */
    private String evidenceJson;
    private String sourceConversationId;
    private String sourceTraceIds;

    private Integer occurrences;
    private OffsetDateTime firstSeenAt;
    private OffsetDateTime lastSeenAt;

    private String status;
    /** Reviewer-authored statement of what the Agent *should* do; required before approval. */
    private String expectedBehavior;
    private String confirmedBy;
    private OffsetDateTime confirmedAt;
    /** Isolated environment (e.g. local test DB, staging) where the candidate was reproduced. */
    private String reproduceEnv;
    private OffsetDateTime reproducedAt;
    private String reproduceRunId;
    private String promoteNote;
    /** Case id assigned in cases.jsonl once promoted. */
    private String promotedCaseId;
}
