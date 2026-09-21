package com.rcdis.agent.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import lombok.Getter;
import lombok.Setter;

/** One deterministic executable step inside an Agent task. */
@Getter
@Setter
@TableName("agent_task_step")
public class AgentTaskStepEntity extends BaseEntity {

    public static final String STATUS_PLANNED = "PLANNED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    private Long taskId;
    private Integer stepNo;
    private String action;
    private String targetType;
    private String targetId;
    private String inputJson;
    private String outputJson;
    private String status;
    private String errorMessage;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private String executionOwner;
    private OffsetDateTime leaseUntil;
    private Integer executionAttempts;

    @Version
    private Integer version;
}
