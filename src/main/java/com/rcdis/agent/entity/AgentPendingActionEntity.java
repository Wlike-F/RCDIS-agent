package com.rcdis.agent.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import lombok.Getter;
import lombok.Setter;

/**
 * A proposed (not yet executed) high-risk Agent write operation.
 *
 * <p>Write tools never mutate data directly. They insert a {@code PENDING} row here carrying the
 * fully-resolved executable arguments plus before/after snapshots, emit a
 * {@code requires_confirmation} SSE event, and only when the user approves through
 * {@code POST /api/chat/confirm} does the real service call run and the row move to
 * {@code EXECUTED}. This keeps the LLM out of the write path entirely.</p>
 */
@Getter
@Setter
@TableName("agent_pending_action")
public class AgentPendingActionEntity extends BaseEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_EXECUTED = "EXECUTED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_FAILED = "FAILED";

    private String conversationId;
    /** The {@code @Tool} name this proposal maps to, e.g. {@code record_expense}. */
    private String toolName;
    /** Fully-resolved executable arguments as JSON (ids resolved, optimistic-lock version captured). */
    private String argumentsJson;
    /** Human-readable one-line description shown on the confirmation card. */
    private String summary;
    private String targetType;
    private String targetId;
    private String beforeSnapshot;
    private String afterSnapshot;
    private String reason;
    private String scope;
    private String status;
    private String commandId;
    private String actorUsername;
    private String actorRoles;
    private String executionOwner;
    private OffsetDateTime leaseUntil;
    private Integer executionAttempts;
    private String resultMessage;
    private String errorMessage;
    private OffsetDateTime expiresAt;
    private OffsetDateTime executedAt;

    @Version
    private Integer version;
}
