package com.rcdis.agent.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * Role-aware overview payload for the dashboard page: admins receive the global operations view,
 * researchers receive a personal "my funding workbench" view. A single endpoint drives the whole
 * page so the two roles never mix scopes.
 */
public record OverviewSummaryVO(
        String role,
        String displayName,
        String generatedAt,
        // ---- admin: global operations block ----
        Integer projectCount,
        BigDecimal totalBudget,
        Integer enabledProviders,
        Integer totalProviders,
        Long pendingApprovalCount,
        // ---- member: personal block ----
        Long myDraftCount,
        Long mySubmittedCount,
        Long myApprovedCount,
        Long myRejectedCount,
        BigDecimal mySubmittedAmount,
        BigDecimal myApprovedAmount,
        Integer myProjectCount,
        BigDecimal myProjectsTotalBudget,
        BigDecimal myProjectsAvailable,
        List<MyProjectVO> myProjects
) {

    /** One project the researcher is responsible for, with its budget headroom. */
    public record MyProjectVO(
            Long projectId,
            String projectCode,
            String projectName,
            String status,
            BigDecimal totalBudget,
            BigDecimal available
    ) {
    }
}
