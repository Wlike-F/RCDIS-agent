package com.rcdis.agent.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("audit_log")
public class AuditLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String actor;
    private String tenantId;
    private String action;
    private String targetType;
    private String targetId;
    private String beforeSnapshot;
    private String afterSnapshot;
    private String reason;
    private String source;
    private String conversationId;
    private OffsetDateTime createdAt;
}
