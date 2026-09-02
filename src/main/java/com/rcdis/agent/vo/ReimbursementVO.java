package com.rcdis.agent.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.rcdis.agent.entity.ReimbursementOrderEntity;
import com.rcdis.agent.entity.ResearchProjectEntity;

public record ReimbursementVO(
        Long id,
        String reimbursementNo,
        Long projectId,
        String projectCode,
        String projectName,
        String applicant,
        String principalInvestigator,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal totalAmount,
        int itemCount,
        String status,
        OffsetDateTime submittedAt,
        OffsetDateTime approvedAt,
        String rejectReason,
        OffsetDateTime createdAt,
        Integer version
) {

    public static ReimbursementVO fromEntity(
            ReimbursementOrderEntity order,
            ResearchProjectEntity project,
            int itemCount) {
        return new ReimbursementVO(
                order.getId(),
                order.getReimbursementNo(),
                order.getProjectId(),
                project == null ? null : project.getProjectCode(),
                project == null ? null : project.getProjectName(),
                order.getApplicant(),
                project == null ? null : project.getPrincipalInvestigator(),
                order.getTotalAmount(),
                itemCount,
                order.getStatus(),
                order.getSubmittedAt(),
                order.getApprovedAt(),
                order.getRejectReason(),
                order.getCreatedAt(),
                order.getVersion());
    }
}
