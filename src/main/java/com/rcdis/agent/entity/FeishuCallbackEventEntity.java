package com.rcdis.agent.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Inbound Feishu callback record. Provides idempotency (Feishu retries a card callback when it is
 * not answered within 3 seconds) and an audit trail of who clicked what.
 *
 * <p>The table has no soft-delete or version columns, so this entity does not extend
 * {@link BaseEntity}.</p>
 */
@Getter
@Setter
@TableName("feishu_callback_event")
public class FeishuCallbackEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String eventType;
    /** Feishu open_id of the user who triggered the callback. */
    private String senderId;
    private String chatId;
    private String messageId;
    private String payload;
    private String idempotencyKey;
    private Boolean processed;
    private OffsetDateTime processedAt;
    private OffsetDateTime createdAt;
}
