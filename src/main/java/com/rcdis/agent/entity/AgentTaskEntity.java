package com.rcdis.agent.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import lombok.Getter;
import lombok.Setter;

/** Durable state of one bounded Plan-and-Execute workflow. */
@Getter
@Setter
@TableName("agent_task")
public class AgentTaskEntity extends BaseEntity {

    public static final String TYPE_REIMBURSEMENT_SUBMISSION = "REIMBURSEMENT_SUBMISSION";
    public static final String STATUS_PLANNED = "PLANNED";
    public static final String STATUS_WAITING_CONFIRMATION = "WAITING_CONFIRMATION";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_PARTIAL = "PARTIAL";

    private String conversationId;
    private String taskType;
    private String title;
    private String status;
    private String inputJson;
    private String planJson;
    private Integer totalSteps;
    private Integer completedSteps;
    private Integer failedSteps;
    private String errorMessage;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private String executionOwner;
    private OffsetDateTime leaseUntil;
    private Integer executionAttempts;

    @Version
    private Integer version;
}
