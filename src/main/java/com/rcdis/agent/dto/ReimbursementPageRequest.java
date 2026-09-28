package com.rcdis.agent.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReimbursementPageRequest(
        @Min(1) long current,
        @Min(1) @Max(500) long size,
        Long projectId,
        @Pattern(regexp = "(?i)^(DRAFT|SUBMITTED|APPROVED|REJECTED|VOID)$", message = "status 只能是 DRAFT/SUBMITTED/APPROVED/REJECTED/VOID") String status,
        @Size(max = 128) String keyword,
        /** Applicant login name; ignored for non-privileged callers whose scope is already self. */
        @Size(max = 64) String applicant,
        @Pattern(regexp = "reimbursement|public_payment", message = "paymentType 只能是 reimbursement 或 public_payment")
        String paymentType,
        @Min(value = 0, message = "minAmount 不能为负") BigDecimal minAmount,
        @Min(value = 0, message = "maxAmount 不能为负") BigDecimal maxAmount
) {

    /** Legacy shape kept so existing callers (tools, tests) compile unchanged. */
    public ReimbursementPageRequest(long current, long size, Long projectId, String status, String keyword) {
        this(current, size, projectId, status, keyword, null, null, null, null);
    }
}
