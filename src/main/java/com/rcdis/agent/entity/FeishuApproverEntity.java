package com.rcdis.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import lombok.Getter;
import lombok.Setter;

/**
 * Binds a Feishu open_id to a system user who is allowed to approve reimbursements.
 *
 * <p>This binding is the authorization source for interactive approval cards. {@code userName} is
 * also compared against {@code reimbursement_order.applicant} so that an applicant cannot approve
 * their own order.</p>
 */
@Getter
@Setter
@TableName("feishu_approver")
public class FeishuApproverEntity extends BaseEntity {

    private String openId;
    private String userId;
    private String userName;
    private String tenantId;
    /** {@code APPROVER} or {@code ADMIN}. */
    private String role;
    private String remark;
    private String status;

    @Version
    private Integer version;
}
