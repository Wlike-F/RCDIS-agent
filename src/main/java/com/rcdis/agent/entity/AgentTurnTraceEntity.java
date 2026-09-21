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

    @TableField(value = "created_at")
    private OffsetDateTime createdAt;
}
