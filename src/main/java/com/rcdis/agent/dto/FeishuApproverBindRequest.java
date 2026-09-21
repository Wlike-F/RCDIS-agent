package com.rcdis.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Binds a Feishu user as a reimbursement approver.
 */
public record FeishuApproverBindRequest(
        @NotBlank @Size(max = 128) String openId,
        @NotBlank @Size(max = 64) String userId,
        @NotBlank @Size(max = 128) String userName,
        @Size(max = 64) String tenantId,
        @Pattern(regexp = "APPROVER|ADMIN", message = "role 只能是 APPROVER 或 ADMIN") String role,
        @Size(max = 500) String remark
) {
}
