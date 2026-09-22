package com.rcdis.agent.service.impl;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.entity.ReimbursementOrderEntity;
import com.rcdis.agent.entity.ResearchProjectEntity;
import com.rcdis.agent.mapper.ReimbursementOrderMapper;
import com.rcdis.agent.mapper.ResearchProjectMapper;
import com.rcdis.agent.service.ModelProviderService;
import com.rcdis.agent.service.OverviewService;
import com.rcdis.agent.vo.ModelProviderVO;
import com.rcdis.agent.vo.OverviewSummaryVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Aggregates the dashboard overview per role. Scope rules:
 *
 * <ul>
 *   <li>ADMIN → global: all projects, total budget, provider availability, pending approvals.</li>
 *   <li>others → personal: my reimbursement orders (the list API already scopes by applicant),
 *       plus the budget headroom of projects where I am the responsible PI.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverviewServiceImpl implements OverviewService {

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_SUBMITTED = "submitted";
    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_REJECTED = "rejected";

    private final ResearchProjectMapper researchProjectMapper;
    private final ReimbursementOrderMapper reimbursementOrderMapper;
    private final ModelProviderService modelProviderService;

    @Override
    public OverviewSummaryVO summary(CurrentUserTO user) {
        boolean admin = user.hasRole("ADMIN");
        if (admin) {
            return adminSummary(user);
        }
        return memberSummary(user);
    }

    private OverviewSummaryVO adminSummary(CurrentUserTO user) {
        List<ResearchProjectEntity> projects = researchProjectMapper.selectList(null);
        BigDecimal totalBudget = projects.stream()
                .map(ResearchProjectEntity::getTotalBudget)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ModelProviderVO> providers = modelProviderService.listProviders();
        long enabled = providers.stream().filter(ModelProviderVO::enabled).count();
        Long pendingApprovals = reimbursementOrderMapper.selectCount(
                new LambdaQueryWrapper<ReimbursementOrderEntity>()
                        .eq(ReimbursementOrderEntity::getStatus, STATUS_SUBMITTED));

        return new OverviewSummaryVO("ADMIN", user.username(), OffsetDateTime.now().toString(),
                projects.size(), totalBudget, (int) enabled, providers.size(), pendingApprovals,
                null, null, null, null, null, null, null, null, null,
                List.<OverviewSummaryVO.MyProjectVO>of());
    }

    private OverviewSummaryVO memberSummary(CurrentUserTO user) {
        String username = user.username();
        List<ReimbursementOrderEntity> myOrders = reimbursementOrderMapper.selectList(
                new LambdaQueryWrapper<ReimbursementOrderEntity>()
                        .eq(ReimbursementOrderEntity::getApplicant, username));

        long drafts = 0;
        long submitted = 0;
        long approved = 0;
        long rejected = 0;
        BigDecimal submittedAmount = BigDecimal.ZERO;
        BigDecimal approvedAmount = BigDecimal.ZERO;
        for (ReimbursementOrderEntity order : myOrders) {
            String status = order.getStatus() == null ? "" : order.getStatus();
            BigDecimal amount = order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount();
            switch (status) {
                case STATUS_DRAFT -> drafts++;
                case STATUS_SUBMITTED -> {
                    submitted++;
                    submittedAmount = submittedAmount.add(amount);
                }
                case STATUS_APPROVED -> {
                    approved++;
                    approvedAmount = approvedAmount.add(amount);
                }
                case STATUS_REJECTED -> rejected++;
                default -> {
                }
            }
        }

        List<ResearchProjectEntity> myProjects = researchProjectMapper.selectList(
                new LambdaQueryWrapper<ResearchProjectEntity>()
                        .eq(ResearchProjectEntity::getPrincipalInvestigator, username)
                        .orderByDesc(ResearchProjectEntity::getId));
        BigDecimal projectsBudget = BigDecimal.ZERO;
        BigDecimal projectsAvailable = BigDecimal.ZERO;
        List<OverviewSummaryVO.MyProjectVO> projectVOs = myProjects.stream()
                .map(project -> {
                    BigDecimal total = project.getTotalBudget() == null
                            ? BigDecimal.ZERO
                            : project.getTotalBudget();
                    BigDecimal used = project.getUsedAmount() == null
                            ? BigDecimal.ZERO
                            : project.getUsedAmount();
                    BigDecimal frozen = project.getFrozenAmount() == null
                            ? BigDecimal.ZERO
                            : project.getFrozenAmount();
                    return new OverviewSummaryVO.MyProjectVO(
                            project.getId(),
                            project.getProjectCode(),
                            project.getProjectName(),
                            project.getStatus(),
                            total,
                            total.subtract(used).subtract(frozen));
                })
                .toList();
        for (OverviewSummaryVO.MyProjectVO project : projectVOs) {
            projectsBudget = projectsBudget.add(project.totalBudget());
            projectsAvailable = projectsAvailable.add(project.available());
        }

        return new OverviewSummaryVO("MEMBER", username, OffsetDateTime.now().toString(),
                null, null, null, null, null,
                drafts, submitted, approved, rejected, submittedAmount, approvedAmount,
                projectVOs.size(), projectsBudget, projectsAvailable,
                projectVOs);
    }
}
