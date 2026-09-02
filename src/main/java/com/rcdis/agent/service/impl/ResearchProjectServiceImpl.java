package com.rcdis.agent.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rcdis.agent.common.aop.AuditOperation;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.common.util.MoneyUtils;
import com.rcdis.agent.dto.ProjectCreateRequest;
import com.rcdis.agent.dto.ProjectDeleteRequest;
import com.rcdis.agent.dto.ProjectPageRequest;
import com.rcdis.agent.dto.ProjectUpdateRequest;
import com.rcdis.agent.entity.BudgetCategoryEntity;
import com.rcdis.agent.entity.ResearchProjectEntity;
import com.rcdis.agent.mapper.BudgetCategoryMapper;
import com.rcdis.agent.mapper.ResearchProjectMapper;
import com.rcdis.agent.service.ResearchProjectService;
import com.rcdis.agent.vo.ProjectVO;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ResearchProjectServiceImpl implements ResearchProjectService {

    private static final String DEFAULT_CATEGORY_CODE = "DEFAULT";

    private final ResearchProjectMapper researchProjectMapper;
    private final BudgetCategoryMapper budgetCategoryMapper;

    @Override
    public PageResponse<ProjectVO> pageProjects(ProjectPageRequest request) {
        validatePage(request.current(), request.size());
        Page<ResearchProjectEntity> page = new Page<>(request.current(), request.size());
        LambdaQueryWrapper<ResearchProjectEntity> wrapper = new LambdaQueryWrapper<ResearchProjectEntity>()
                .eq(StringUtils.hasText(request.status()), ResearchProjectEntity::getStatus, request.status())
                .and(StringUtils.hasText(request.keyword()), condition -> condition
                        .like(ResearchProjectEntity::getProjectCode, request.keyword())
                        .or()
                        .like(ResearchProjectEntity::getProjectName, request.keyword())
                        .or()
                        .like(ResearchProjectEntity::getPrincipalInvestigator, request.keyword())
                        .or()
                        .like(ResearchProjectEntity::getFundingSource, request.keyword()))
                .orderByDesc(ResearchProjectEntity::getUpdatedAt)
                .orderByDesc(ResearchProjectEntity::getId);

        Page<ResearchProjectEntity> entityPage = researchProjectMapper.selectPage(page, wrapper);
        Map<Long, BigDecimal> remainingBudgetByProjectId = remainingBudgetByProjectId(entityPage.getRecords());
        Page<ProjectVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream()
                .map(entity -> ProjectVO.fromEntity(
                        entity,
                        remainingBudgetByProjectId.getOrDefault(
                                entity.getId(),
                                MoneyUtils.normalize(entity.getTotalBudget()))))
                .toList());
        return PageResponse.fromPage(voPage);
    }

    @Override
    public ProjectVO getProject(Long id) {
        ResearchProjectEntity entity = findProjectEntity(id);
        BigDecimal remainingBudget = remainingBudgetByProjectId(List.of(entity))
                .getOrDefault(entity.getId(), MoneyUtils.normalize(entity.getTotalBudget()));
        return ProjectVO.fromEntity(entity, remainingBudget);
    }

    @Override
    @Transactional
    @AuditOperation(action = "CREATE_RESEARCH_PROJECT", targetType = "RESEARCH_PROJECT")
    public ProjectVO createProject(ProjectCreateRequest request) {
        validateDateRange(request.startDate(), request.endDate());
        String projectCode = normalizeRequiredText(request.projectCode(), "projectCode");
        ensureProjectCodeIsAvailable(projectCode);

        ResearchProjectEntity entity = new ResearchProjectEntity();
        entity.setProjectCode(projectCode);
        entity.setProjectName(normalizeRequiredText(request.projectName(), "projectName"));
        entity.setPrincipalInvestigator(normalizeOptionalText(request.principalInvestigator()));
        entity.setFundingSource(normalizeOptionalText(request.fundingSource()));
        entity.setTotalBudget(normalizeNonNegativeAmount(request.totalBudget(), "totalBudget"));
        entity.setStartDate(request.startDate());
        entity.setEndDate(request.endDate());
        entity.setStatus(request.status());
        entity.setVersion(Integer.valueOf(0));

        researchProjectMapper.insert(entity);

        // Quick mode: auto-create a default budget category equal to totalBudget so that
        // small projects can register expenses without configuring categories manually.
        if (!Boolean.FALSE.equals(request.quickMode())) {
            createDefaultBudgetCategory(entity);
        }
        return getProject(entity.getId());
    }

    private void createDefaultBudgetCategory(ResearchProjectEntity project) {
        BudgetCategoryEntity category = new BudgetCategoryEntity();
        category.setProjectId(project.getId());
        category.setCategoryCode(DEFAULT_CATEGORY_CODE);
        category.setCategoryName("默认科目");
        category.setAllocatedAmount(MoneyUtils.normalize(project.getTotalBudget()));
        category.setUsedAmount(BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE, MoneyUtils.MONEY_ROUNDING_MODE));
        category.setFrozenAmount(BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE, MoneyUtils.MONEY_ROUNDING_MODE));
        category.setStatus("ACTIVE");
        category.setRemark("快捷模式自动创建，与项目总预算等额，可修改或停用");
        category.setVersion(Integer.valueOf(0));
        budgetCategoryMapper.insert(category);
    }

    @Override
    @Transactional
    @AuditOperation(action = "UPDATE_RESEARCH_PROJECT", targetType = "RESEARCH_PROJECT")
    public ProjectVO updateProject(Long id, ProjectUpdateRequest request) {
        ResearchProjectEntity existing = findProjectEntity(id);
        ensureVersionMatches(existing.getVersion(), request.version(), "RESEARCH_PROJECT_VERSION_CONFLICT");
        validateDateRange(request.startDate(), request.endDate());

        BigDecimal totalBudget = normalizeNonNegativeAmount(request.totalBudget(), "totalBudget");
        BigDecimal allocatedAmount = allocatedAmount(id);
        if (MoneyUtils.lessThan(totalBudget, allocatedAmount)) {
            throw new BusinessException(
                    "PROJECT_BUDGET_LESS_THAN_ALLOCATED",
                    "Project totalBudget must not be less than allocated budget categories. projectId="
                            + id + ", totalBudget=" + totalBudget + ", allocatedAmount=" + allocatedAmount);
        }

        ResearchProjectEntity updateEntity = new ResearchProjectEntity();
        updateEntity.setId(id);
        updateEntity.setProjectName(normalizeRequiredText(request.projectName(), "projectName"));
        updateEntity.setPrincipalInvestigator(normalizeOptionalText(request.principalInvestigator()));
        updateEntity.setFundingSource(normalizeOptionalText(request.fundingSource()));
        updateEntity.setTotalBudget(totalBudget);
        updateEntity.setStartDate(request.startDate());
        updateEntity.setEndDate(request.endDate());
        updateEntity.setStatus(request.status());
        updateEntity.setVersion(request.version());

        int updated = researchProjectMapper.updateById(updateEntity);
        if (updated != 1) {
            throw new BusinessException(
                    "RESEARCH_PROJECT_VERSION_CONFLICT",
                    "Research project was changed by another request. projectId=" + id,
                    HttpStatus.CONFLICT);
        }
        return getProject(id);
    }

    @Override
    @Transactional
    @AuditOperation(action = "DELETE_RESEARCH_PROJECT", targetType = "RESEARCH_PROJECT")
    public void deleteProject(Long id, ProjectDeleteRequest request) {
        ResearchProjectEntity existing = findProjectEntity(id);
        ensureVersionMatches(existing.getVersion(), request.version(), "RESEARCH_PROJECT_VERSION_CONFLICT");
        ensureProjectCanBeDeleted(id);

        OffsetDateTime now = OffsetDateTime.now();
        String currentUserId = CurrentUserContextHolder.currentOrAnonymous().userId();
        int updated = researchProjectMapper.update(null, new LambdaUpdateWrapper<ResearchProjectEntity>()
                .eq(ResearchProjectEntity::getId, id)
                .eq(ResearchProjectEntity::getVersion, request.version())
                .set(ResearchProjectEntity::getDeleted, Integer.valueOf(1))
                .set(ResearchProjectEntity::getDeletedAt, now)
                .set(ResearchProjectEntity::getDeletedBy, currentUserId)
                .set(ResearchProjectEntity::getDeleteReason, normalizeRequiredText(request.reason(), "reason"))
                .set(ResearchProjectEntity::getUpdatedAt, now)
                .set(ResearchProjectEntity::getUpdatedBy, currentUserId)
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw new BusinessException(
                    "RESEARCH_PROJECT_VERSION_CONFLICT",
                    "Research project was changed by another request. projectId=" + id,
                    HttpStatus.CONFLICT);
        }
    }

    private ResearchProjectEntity findProjectEntity(Long id) {
        ResearchProjectEntity entity = researchProjectMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(
                    "RESEARCH_PROJECT_NOT_FOUND",
                    "Research project was not found. projectId=" + id,
                    HttpStatus.NOT_FOUND);
        }
        return entity;
    }

    private void ensureProjectCodeIsAvailable(String projectCode) {
        Long count = researchProjectMapper.selectCount(new LambdaQueryWrapper<ResearchProjectEntity>()
                .eq(ResearchProjectEntity::getProjectCode, projectCode));
        if (count > 0) {
            throw new BusinessException(
                    "RESEARCH_PROJECT_CODE_EXISTS",
                    "Research project code already exists. projectCode=" + projectCode,
                    HttpStatus.CONFLICT);
        }
    }

    private void ensureProjectCanBeDeleted(Long projectId) {
        Long budgetCategoryCount = budgetCategoryMapper.selectCount(new LambdaQueryWrapper<BudgetCategoryEntity>()
                .eq(BudgetCategoryEntity::getProjectId, projectId));
        if (budgetCategoryCount > 0) {
            throw new BusinessException(
                    "PROJECT_HAS_BUDGET_CATEGORIES",
                    "Research project has active budget categories. Delete budget categories first. projectId="
                            + projectId,
                    HttpStatus.CONFLICT);
        }
    }

    private BigDecimal allocatedAmount(Long projectId) {
        return budgetCategoryMapper.selectList(new LambdaQueryWrapper<BudgetCategoryEntity>()
                        .eq(BudgetCategoryEntity::getProjectId, projectId)
                        .eq(BudgetCategoryEntity::getStatus, "ACTIVE"))
                .stream()
                .map(BudgetCategoryEntity::getAllocatedAmount)
                .reduce(BigDecimal.ZERO, MoneyUtils::add);
    }

    private Map<Long, BigDecimal> remainingBudgetByProjectId(List<ResearchProjectEntity> projects) {
        if (projects.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> projectIds = projects.stream()
                .map(ResearchProjectEntity::getId)
                .collect(Collectors.toSet());
        Map<Long, BigDecimal> usedAndFrozenByProjectId = budgetCategoryMapper.selectList(
                        new LambdaQueryWrapper<BudgetCategoryEntity>()
                                .in(BudgetCategoryEntity::getProjectId, projectIds)
                                .eq(BudgetCategoryEntity::getStatus, "ACTIVE"))
                .stream()
                .collect(Collectors.toMap(
                        BudgetCategoryEntity::getProjectId,
                        entity -> MoneyUtils.add(entity.getUsedAmount(), entity.getFrozenAmount()),
                        MoneyUtils::add));
        return projects.stream()
                .collect(Collectors.toMap(
                        ResearchProjectEntity::getId,
                        entity -> MoneyUtils.subtract(
                                MoneyUtils.normalize(entity.getTotalBudget()),
                                usedAndFrozenByProjectId.getOrDefault(entity.getId(), zeroAmount()))));
    }

    private BigDecimal zeroAmount() {
        return BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE, MoneyUtils.MONEY_ROUNDING_MODE);
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new BusinessException(
                    "PROJECT_DATE_RANGE_INVALID",
                    "Project endDate must not be before startDate. startDate=" + startDate + ", endDate=" + endDate);
        }
    }

    private void validatePage(long current, long size) {
        if (current < 1) {
            throw new BusinessException("PAGE_CURRENT_INVALID", "Page current must be greater than 0");
        }
        if (size < 1 || size > 500) {
            throw new BusinessException("PAGE_SIZE_INVALID", "Page size must be between 1 and 500");
        }
    }

    private void ensureVersionMatches(Integer currentVersion, Integer requestVersion, String errorCode) {
        if (!currentVersion.equals(requestVersion)) {
            throw new BusinessException(
                    errorCode,
                    "Record was changed by another request. currentVersion="
                            + currentVersion + ", requestVersion=" + requestVersion,
                    HttpStatus.CONFLICT);
        }
    }

    private BigDecimal normalizeNonNegativeAmount(BigDecimal amount, String fieldName) {
        if (amount == null) {
            throw new BusinessException("MONEY_AMOUNT_REQUIRED", fieldName + " must not be null");
        }
        if (amount.signum() < 0) {
            throw new BusinessException("MONEY_AMOUNT_NEGATIVE", fieldName + " must not be negative");
        }
        return MoneyUtils.normalize(amount);
    }

    private String normalizeRequiredText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException("TEXT_REQUIRED", fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String normalizeOptionalText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
