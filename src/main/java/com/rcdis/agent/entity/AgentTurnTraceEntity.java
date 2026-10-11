package com.rcdis.agent.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * One row per Agent turn for observability.
 *
 * <p>Append-only (no soft delete): records token usage, first-token / total latency and the ordered
 * tool-call chain so the workbench "context trace" tab can replay what a turn did and what it cost.
 * Deliberately does not extend {@link BaseEntity} because traces are immutable audit-style data.</p>
 */
@Getter
@Setter
@TableName("agent_turn_trace")
public class AgentTurnTraceEntity {

    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;
    private Integer turnSeq;
    private String providerCode;
    private String modelName;
    private String status;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Long firstTokenMs;
    private Long totalMs;
    /** Ordered tool-call chain as JSON: [{name, ok, durationMs, status}]. */
    private String toolCallsJson;
    private String errorMessage;

    /**
     * Highest halt tier reached this turn ({@code SOFT_HINT} / {@code HARD_HALT} / {@code FREEZE}
     * / {@code CEILING}); null when the circuit breaker never fired. Written by
     * {@code RecordingToolCallback} through {@code TurnTraceCollector.setHaltReason}.
     */
    private String haltReason;
    /** Aggregate count of failed tool calls this turn; the trace uses it to answer "did this turn halt". */
    private Integer haltCount;
    /** JSON array of tool names frozen for the rest of the turn (survives across retries of the same turn). */
    private String frozenToolsJson;

    @TableField(value = "created_at")
    private OffsetDateTime createdAt;
}
