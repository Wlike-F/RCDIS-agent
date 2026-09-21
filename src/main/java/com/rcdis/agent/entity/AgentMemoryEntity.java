package com.rcdis.agent.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * A cross-session semantic fact about a user, extracted asynchronously from conversations.
 *
 * <p>Content is an extractive snippet (original wording) so numbers/identifiers are never
 * paraphrased. Injected into future sessions' prompt prefix when the read switch is on.</p>
 */
@Getter
@Setter
@TableName("agent_memory")
public class AgentMemoryEntity {

    public static final String SCOPE_USER = "USER";
    public static final String SCOPE_PROJECT = "PROJECT";
    public static final String SCOPE_GLOBAL = "GLOBAL";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String scope;
    private String ownerUserId;
    private Long projectId;
    private String factType;
    private String content;
    private String sourceConversationId;
    private Integer sourceSeq;
    private Integer hitCount;
    private OffsetDateTime lastHitAt;
    private String createdBy;

    @TableField(value = "created_at")
    private OffsetDateTime createdAt;
}
