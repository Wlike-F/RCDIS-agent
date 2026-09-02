package com.rcdis.agent.service.impl;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

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
import com.rcdis.agent.dto.BudgetCategoryCreateRequest;
import com.rcdis.agent.dto.BudgetCategoryDeleteRequest;
import com.rcdis.agent.dto.BudgetCategoryPageRequest;
import com.rcdis.agent.dto.BudgetCategoryUpdateRequest;
import com.rcdis.agent.entity.BudgetCategoryEntity;
import com.rcdis.agent.entity.ResearchProjectEntity;
import com.rcdis.agent.mapper.BudgetCategoryMapper;
import com.rcdis.agent.mapper.ResearchProjectMapper;
import com.rcdis.agent.service.BudgetCategoryService;
import com.rcdis.agent.vo.BudgetCategoryVO;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BudgetCategoryServiceImpl implements BudgetCategoryService {

    private static final String BUDGET_CATEGORY_STATUS_ACTIVE = "ACTIVE";
    private static final String PROJECT_STATUS_CLOSED = "CLOSED";

    private final BudgetCategoryMapper budgetCategoryMapper;
    private final ResearchProjectMapper researchProjectMapper;

    @Override
    public PageResponse<BudgetCategoryVO> pageBudgetCategories(Long projectId, BudgetCategoryPageRequest request) {
        findProjectEntity(projectId);
        validatePage(request.current(), request.size());

        Page<BudgetCategoryEntity> page = new Page<>(request.current(), request.size());
        LambdaQueryWrapper<BudgetCategoryEntity> wrapper = new LambdaQueryWrapper<BudgetCategoryEntity>()
                .eq(BudgetCategoryEntity::getProjectId, projectId)
                .eq(StringUtils.hasText(request.status()), BudgetCategoryEntity::getStatus, request.status())
                .and(StringUtils.hasText(request.keyword()), condition -> condition
                        .like(BudgetCategoryEntity::getCategoryCode, request.keyword())
                        .or()
                        .like(BudgetCategoryEntity::getCategoryName, request.keyword()))
                .orderByDesc(BudgetCategoryEntity::getUpdatedAt)
                .orderByDesc(BudgetCategoryEntity::getId);

        Page<BudgetCategoryEntity> entityPage = budgetCategoryMapper.selectPage(page, wrapper);
        Page<BudgetCategoryVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream()
                .map(BudgetCategoryVO::fromEntity)
                .toList());
        return PageResponse.fromPage(voPage);
    }

    @Override
    public BudgetCategoryVO getBudgetCategory(Long projectId, Long id) {
        return BudgetCategoryVO.fromEntity(findBudgetCategoryEntity(projectId, id));
    }

    @Override
    @Transactional
    @AuditOperation(action = "CREATE_BUDGET_CATEGORY", targetType = "BUDGET_CATEGORY")
    public BudgetCategoryVO createBudgetCategory(Long projectId, BudgetCategoryCreateRequest request) {
        ResearchProjectEntity project = findProjectEntity(projectId);
        ensureProjectBudgetCanBeChanged(project);
        String categoryCode = normalizeRequiredText(request.categoryCode(), "categoryCode");
        ensureCategoryCodeIsAvailable(projectId, categoryCode);

        BigDecimal allocatedAmount = normalizeNonNegativeAmount(request.allocatedAmount(), "allocatedAmount");
        ensureBudgetLimit(project, null, allocatedAmount);

        BudgetCategoryEntity entity = new BudgetCategoryEntity();
        entity.setProjectId(projectId);
        entity.setCategoryCode(categoryCode);
        entity.setCategoryName(normalizeRequiredText(request.categoryName(), "categoryName"));
        entity.setAllocatedAmount(allocatedAmount);
        entity.setUsedAmount(BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE, MoneyUtils.MONEY_ROUNDING_MODE));
        entity.setFrozenAmount(BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE, MoneyUtils.MONEY_ROUNDING_MODE));
        entity.setStatus(request.status());
        entity.setRemark(normalizeOptionalText(request.remark()));
        entity.setVersion(Integer.valueOf(0));

        budgetCategoryMapper.insert(entity);
        return getBudgetCategory(projectId, entity.getId());
    }

    @Override
    @Transactional
    @AuditOperation(action = "UPDATE_BUDGET_CATEGORY", targetType = "BUDGET_CATEGORY")
    public BudgetCategoryVO updateBudgetCategory(Long projectId, Long id, BudgetCategoryUpdateRequest request) {
        ResearchProjectEntity project = findProjectEntity(projectId);
        ensureProjectBudgetCanBeChanged(project);
        BudgetCategoryEntity existing = findBudgetCategoryEntity(projectId, id);
        ensureVersionMatches(existing.getVersion(), request.version(), "BUDGET_CATEGORY_VERSION_CONFLICT");

        BigDecimal allocatedAmount = normalizeNonNegativeAmount(request.allocatedAmount(), "allocatedAmount");
        BigDecimal reservedAmount = MoneyUtils.add(existing.getUsedAmount(), existing.getFrozenAmount());
        if (MoneyUtils.lessThan(allocatedAmount, reservedAmount)) {
            throw new BusinessException(
                    "BUDGET_CATEGORY_ALLOCATED_LESS_THAN_RESERVED",
                    "Budget category allocatedAmount must not be less than usedAmount plus frozenAmount. budgetCategoryId="
                            + id + ", allocatedAmount=" + allocatedAmount + ", reservedAmount=" + reservedAmount);
        }
        ensureBudgetLimit(project, id, allocatedAmount);

        BudgetCategoryEntity updateEntity = new BudgetCategoryEntity();
        updateEntity.setId(id);
        updateEntity.setProjectId(projectId);
        updateEntity.setCategoryName(normalizeRequiredText(request.categoryName(), "categoryName"));
        updateEntity.setAllocatedAmount(allocatedAmount);
        updateEntity.setStatus(request.status());
        updateEntity.setRemark(normalizeOptionalText(request.remark()));
        updateEntity.setVersion(request.version());

        int updated = budgetCategoryMapper.updateById(updateEntity);
        if (updated != 1) {
            throw new BusinessException(
                    "BUDGET_CATEGORY_VERSION_CONFLICT",
                    "Budget category was changed by another request. budgetCategoryId=" + id,
                    HttpStatus.CONFLICT);
        }
        return getBudgetCategory(projectId, id);
    }

    @Override
    @Transactional
    @AuditOperation(action = "DELETE_BUDGET_CATEGORY", targetType = "BUDGET_CATEGORY")
    public void deleteBudgetCategory(Long projectId, Long id, BudgetCategoryDeleteRequest request) {
        ResearchProjectEntity project = findProjectEntity(projectId);
        ensureProjectBudgetCanBeChanged(project);
        BudgetCategoryEntity existing = findBudgetCategoryEntity(projectId, id);
        ensureVersionMatches(existing.getVersion(), request.version(), "BUDGET_CATEGORY_VERSION_CONFLICT");
        ensureBudgetCategoryCanBeDeleted(existing);

        OffsetDateTime now = OffsetDateTime.now();
        String currentUserId = CurrentUserContextHolder.currentOrAnonymous().userId();
        int updated = budgetCategoryMapper.update(null, new LambdaUpdateWrapper<BudgetCategoryEntity>()
                .eq(BudgetCategoryEntity::getProjectId, projectId)
                .eq(BudgetCategoryEntity::getId, id)
                .eq(BudgetCategoryEntity::getVersion, request.version())
                .set(BudgetCategoryEntity::getDeleted, Integer.valueOf(1))
                .set(BudgetCategoryEntity::getDeletedAt, now)
                .set(BudgetCategoryEntity::getDeletedBy, currentUserId)
                .set(BudgetCategoryEntity::getDeleteReason, normalizeRequiredText(request.reason(), "reason"))
                .set(BudgetCategoryEntity::getUpdatedAt, now)
                .set(BudgetCategoryEntity::getUpdatedBy, currentUserId)
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw new BusinessException(
                    "BUDGET_CATEGORY_VERSION_CONFLICT",
                    "Budget category was changed by another request. budgetCategoryId=" + id,
                    HttpStatus.CONFLICT);
        }
    }

    private ResearchProjectEntity findProjectEntity(Long projectId) {
        ResearchProjectEntity entity = researchProjectMapper.selectById(projectId);
        if (entity == null) {
            throw new BusinessException(
                    "RESEARCH_PROJECT_NOT_FOUND",
                    "Research project was not found. projectId=" + projectId,
                    HttpStatus.NOT_FOUND);
        }
        return entity;
    }

    private BudgetCategoryEntity findBudgetCategoryEntity(Long projectId, Long id) {
        BudgetCategoryEntity entity = budgetCategoryMapper.selectOne(new LambdaQueryWrapper<BudgetCategoryEntity>()
                .eq(BudgetCategoryEntity::getProjectId, projectId)
                .eq(BudgetCategoryEntity::getId, id));
        if (entity == null) {
            throw new BusinessException(
                    "BUDGET_CATEGORY_NOT_FOUND",
                    "Budget category was not found. projectId=" + projectId + ", budgetCategoryId=" + id,
                    HttpStatus.NOT_FOUND);
        }
        return entity;
    }

    private void ensureProjectBudgetCanBeChanged(ResearchProjectEntity project) {
        if (PROJECT_STATUS_CLOSED.equals(project.getStatus())) {
            throw new BusinessException(
                    "PROJECT_CLOSED",
                    "Closed project budget cannot be changed. projectId=" + project.getId(),
                    HttpStatus.CONFLICT);
        }
    }

    private void ensureCategoryCodeIsAvailable(Long projectId, String categoryCode) {
        Long count = budgetCategoryMapper.selectCount(new LambdaQueryWrapper<BudgetCategoryEntity>()
                .eq(BudgetCategoryEntity::getProjectId, projectId)
                .eq(BudgetCategoryEntity::getCategoryCode, categoryCode));
        if (count > 0) {
            throw new BusinessException(
                    "BUDGET_CATEGORY_CODE_EXISTS",
                    "Budget category code already exists. projectId=" + projectId + ", categoryCode=" + categoryCode,
                    HttpStatus.CONFLICT);
        }
    }

    private void ensureBudgetLimit(ResearchProjectEntity project, Long excludedBudgetCategoryId, BigDecimal amount) {
        BigDecimal allocatedAmount = allocatedAmount(project.getId(), excludedBudgetCategoryId);
        BigDecimal totalAllocatedAmount = MoneyUtils.add(allocatedAmount, amount);
        BigDecimal projectTotalBudget = MoneyUtils.normalize(project.getTotalBudget());
        if (MoneyUtils.greaterThan(totalAllocatedAmount, projectTotalBudget)) {
            throw new BusinessException(
                    "PROJECT_BUDGET_EXCEEDED",
                    "Allocated budget categories exceed project totalBudget. projectId=" + project.getId()
                            + ", totalAllocatedAmount=" + totalAllocatedAmount
                            + ", totalBudget=" + projectTotalBudget);
        }
    }

    private BigDecimal allocatedAmount(Long projectId, Long excludedBudgetCategoryId) {
        LambdaQueryWrapper<BudgetCategoryEntity> wrapper = new LambdaQueryWrapper<BudgetCategoryEntity>()
                .eq(BudgetCategoryEntity::getProjectId, projectId)
                .eq(BudgetCategoryEntity::getStatus, BUDGET_CATEGORY_STATUS_ACTIVE)
                .ne(excludedBudgetCategoryId != null, BudgetCategoryEntity::getId, excludedBudgetCategoryId);
        return budgetCategoryMapper.selectList(wrapper)
                .stream()
                .map(BudgetCategoryEntity::getAllocatedAmount)
                .reduce(BigDecimal.ZERO, MoneyUtils::add);
    }

    private void ensureBudgetCategoryCanBeDeleted(BudgetCategoryEntity entity) {
        BigDecimal reservedAmount = MoneyUtils.add(entity.getUsedAmount(), entity.getFrozenAmount());
        if (reservedAmount.signum() > 0) {
            throw new BusinessException(
                    "BUDGET_CATEGORY_RESERVED",
                    "Budget category has used or frozen amount and cannot be deleted. budgetCategoryId="
                            + entity.getId() + ", reservedAmount=" + reservedAmount,
                    HttpStatus.CONFLICT);
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
