package com.rcdis.agent.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
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

    /**
     * pgvector embedding stored as its text literal (e.g. {@code [0.1,0.2,...]}).
     *
     * <p>Excluded from generic CRUD: the PostgreSQL column is typed {@code vector}, which rejects a
     * plain varchar bind, so embeddings are written only through a dedicated mapper statement that
     * casts {@code #{value}::vector}. It is also {@code select=false} so the newest-N injection path
     * never drags the (large) vector text back into memory.</p>
     */
    @TableField(value = "embedding", insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER, select = false)
    private String embedding;

    @TableField(value = "created_at")
    private OffsetDateTime createdAt;
}
