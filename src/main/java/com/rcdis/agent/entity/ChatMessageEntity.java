package com.rcdis.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * One row per Agent conversation turn, stored verbatim in the shape the model sees it.
 *
 * <p>{@code role} follows the OpenAI convention: {@code user} / {@code assistant} / {@code system}
 * / {@code tool}. {@code content} is nullable so an assistant row can be inserted with
 * {@code status='STREAMING'} before the first token arrives, then updated on completion. Ordering
 * within a session is by {@code id ASC}; no explicit sequence column is needed because writes are
 * serialized per conversation.</p>
 */
@Getter
@Setter
@TableName("chat_message")
public class ChatMessageEntity extends BaseEntity {

    private Long sessionId;
    /** Denormalized for cheap history loads without joining chat_session. */
    private String conversationId;
    /** Per-conversation monotonic turn ordinal (1-based); boundary for summary/recall. */
    private Integer seq;
    /** {@code user} / {@code assistant} / {@code system} / {@code tool}. */
    private String role;
    private String content;
    /** Only set on assistant rows, for observability in the UI. */
    private String providerCode;
    private String modelName;
    /** {@code STREAMING} while tokens are arriving, {@code DONE} on success, {@code ERROR} on failure. */
    private String status;
    private String errorMessage;
    private Integer tokenCount;
}
