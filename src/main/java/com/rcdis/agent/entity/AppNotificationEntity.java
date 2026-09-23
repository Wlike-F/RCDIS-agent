package com.rcdis.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * An in-app notification pushed to a single recipient for a reimbursement lifecycle event.
 *
 * <p>{@code recipient} stores the {@code sys_user.username} login name so that it matches the JWT
 * {@code userId} carried by {@code CurrentUserTO} and the actor recorded in audit logs. {@code bizType}
 * plus {@code bizId} let the frontend deep-link to the underlying business record.</p>
 */
@Getter
@Setter
@TableName("app_notification")
public class AppNotificationEntity extends BaseEntity {

    public static final String TYPE_SUBMITTED = "SUBMITTED";
    public static final String TYPE_APPROVED = "APPROVED";
    public static final String TYPE_REJECTED = "REJECTED";
    public static final String TYPE_VOIDED = "VOIDED";

    public static final String BIZ_TYPE_REIMBURSEMENT = "REIMBURSEMENT";

    private String recipient;
    private String type;
    private String title;
    private String content;
    private String bizType;
    private Long bizId;
    private Boolean isRead;
}
