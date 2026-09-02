package com.rcdis.agent.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("notification_outbox")
public class NotificationOutboxEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String channel;
    private String target;
    private String messageType;
    private String payload;
    private String status;
    private String idempotencyKey;
    private String errorMessage;
    private OffsetDateTime sentAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
