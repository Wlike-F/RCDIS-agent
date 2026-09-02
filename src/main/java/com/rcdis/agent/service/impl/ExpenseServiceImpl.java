package com.rcdis.agent.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
import com.rcdis.agent.dto.ExpenseCreateRequest;
import com.rcdis.agent.dto.ExpenseDeleteRequest;
import com.rcdis.agent.dto.ExpensePageRequest;
import com.rcdis.agent.dto.ExpenseUpdateRequest;
import com.rcdis.agent.entity.BudgetCategoryEntity;
import com.rcdis.agent.entity.ExpenseRecordEntity;
import com.rcdis.agent.entity.ResearchProjectEntity;
import com.rcdis.agent.mapper.BudgetCategoryMapper;
import com.rcdis.agent.mapper.ExpenseRecordMapper;
import com.rcdis.agent.mapper.ResearchProjectMapper;
import com.rcdis.agent.service.ExpenseService;
import com.rcdis.agent.vo.ExpenseVO;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ExpenseServiceImpl implements ExpenseService {

    private static final String EXPENSE_STATUS_REGISTERED = "REGISTERED";
    private static final String EXPENSE_STATUS_VOIDED = "VOIDED";
    private static final String PROJECT_STATUS_ACTIVE = "ACTIVE";
    private static final String BUDGET_CATEGORY_STATUS_ACTIVE = "ACTIVE";

    private final ExpenseRecordMapper expenseRecordMapper;
    private final ResearchProjectMapper researchProjectMapper;
    private final BudgetCategoryMapper budgetCategoryMapper;

    @Override
    public PageResponse<ExpenseVO> pageExpenses(ExpensePageRequest request) {
        validatePage(request.current(), request.size());
        validateDateRange(request.startDate(), request.endDate());

        Page<ExpenseRecordEntity> page = new Page<>(request.current(), request.size());
        // Explicit mapper SQL: includes soft-deleted (VOIDED) records, which must stay visible for audit.
        Page<ExpenseRecordEntity> entityPage = expenseRecordMapper.selectExpensePage(
                page,
                request.projectId(),
                request.budgetCategoryId(),
                request.status(),
                request.startDate(),
                request.endDate(),
                StringUtils.trimWhitespace(request.keyword()));
        Map<Long, ResearchProjectEntity> projects = projectsById(entityPage.getRecords());
        Map<Long, BudgetCategoryEntity> budgetCategories = budgetCategoriesById(entityPage.getRecords());
        Page<ExpenseVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream()
                .map(entity -> ExpenseVO.fromEntity(
                        entity,
                        projects.get(entity.getProjectId()),
                        budgetCategories.get(entity.getBudgetCategoryId())))
                .toList());
        return PageResponse.fromPage(voPage);
    }

    @Override
    public ExpenseVO getExpense(Long id) {
        ExpenseRecordEntity expense = findExpenseEntity(id);
        ResearchProjectEntity project = researchProjectMapper.selectById(expense.getProjectId());
        BudgetCategoryEntity budgetCategory = budgetCategoryMapper.selectById(expense.getBudgetCategoryId());
        return ExpenseVO.fromEntity(expense, project, budgetCategory);
    }

    @Override
    @Transactional
    @AuditOperation(action = "CREATE_EXPENSE", targetType = "EXPENSE_RECORD")
    public ExpenseVO createExpense(ExpenseCreateRequest request) {
        ResearchProjectEntity project = findProjectEntity(request.projectId());
        ensureProjectCanRecordExpense(project);
        BudgetCategoryEntity budgetCategory = findActiveBudgetCategoryEntity(
                request.projectId(),
                request.budgetCategoryId());

        BigDecimal amount = normalizePositiveAmount(request.amount(), "amount");
        ensureBudgetCategoryHasAvailableAmount(budgetCategory, amount, zeroAmount());

        ExpenseRecordEntity entity = new ExpenseRecordEntity();
        entity.setProjectId(request.projectId());
        entity.setBudgetCategoryId(request.budgetCategoryId());
        entity.setAmount(amount);
        entity.setExpenseDate(request.expenseDate());
        entity.setVendor(normalizeOptionalText(request.vendor()));
        entity.setInvoiceNo(normalizeOptionalText(request.invoiceNo()));
        entity.setReceiptFile(normalizeOptionalText(request.receiptFile()));
        entity.setDescription(normalizeRequiredText(request.description(), "description"));
        entity.setStatus(EXPENSE_STATUS_REGISTERED);
        entity.setVersion(Integer.valueOf(0));

        expenseRecordMapper.insert(entity);
        applyBudgetUsedAmountDelta(budgetCategory, amount);
        return getExpense(entity.getId());
    }

    @Override
    @Transactional
    @AuditOperation(action = "UPDATE_EXPENSE", targetType = "EXPENSE_RECORD")
    public ExpenseVO updateExpense(Long id, ExpenseUpdateRequest request) {
        ExpenseRecordEntity existing = findExpenseEntity(id);
        ensureExpenseCanBeChanged(existing);
        ensureVersionMatches(existing.getVersion(), request.version(), "EXPENSE_VERSION_CONFLICT");

        ResearchProjectEntity project = findProjectEntity(request.projectId());
        ensureProjectCanRecordExpense(project);
        BudgetCategoryEntity originalBudgetCategory = findBudgetCategoryEntity(
                existing.getProjectId(),
                existing.getBudgetCategoryId());
        BudgetCategoryEntity targetBudgetCategory = findActiveBudgetCategoryEntity(
                request.projectId(),
                request.budgetCategoryId());

        BigDecimal amount = normalizePositiveAmount(request.amount(), "amount");
        BigDecimal releasableAmount = existing.getBudgetCategoryId().equals(request.budgetCategoryId())
                ? normalizeAmount(existing.getAmount(), "existing.amount")
                : zeroAmount();
        ensureBudgetCategoryHasAvailableAmount(targetBudgetCategory, amount, releasableAmount);

        if (existing.getBudgetCategoryId().equals(request.budgetCategoryId())) {
            BigDecimal delta = MoneyUtils.subtract(amount, normalizeAmount(existing.getAmount(), "existing.amount"));
            if (delta.signum() != 0) {
                applyBudgetUsedAmountDelta(targetBudgetCategory, delta);
            }
        } else {
            applyBudgetUsedAmountDelta(originalBudgetCategory, normalizeAmount(existing.getAmount(), "existing.amount").negate());
            applyBudgetUsedAmountDelta(targetBudgetCategory, amount);
        }

        OffsetDateTime now = OffsetDateTime.now();
        String currentUserId = CurrentUserContextHolder.currentOrAnonymous().userId();
        int updated = expenseRecordMapper.update(null, new LambdaUpdateWrapper<ExpenseRecordEntity>()
                .eq(ExpenseRecordEntity::getId, id)
                .eq(ExpenseRecordEntity::getVersion, request.version())
                .set(ExpenseRecordEntity::getProjectId, request.projectId())
                .set(ExpenseRecordEntity::getBudgetCategoryId, request.budgetCategoryId())
                .set(ExpenseRecordEntity::getAmount, amount)
                .set(ExpenseRecordEntity::getExpenseDate, request.expenseDate())
                .set(ExpenseRecordEntity::getVendor, normalizeOptionalText(request.vendor()))
                .set(ExpenseRecordEntity::getInvoiceNo, normalizeOptionalText(request.invoiceNo()))
                .set(ExpenseRecordEntity::getReceiptFile, normalizeOptionalText(request.receiptFile()))
                .set(ExpenseRecordEntity::getDescription, normalizeRequiredText(request.description(), "description"))
                .set(ExpenseRecordEntity::getUpdatedAt, now)
                .set(ExpenseRecordEntity::getUpdatedBy, currentUserId)
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw new BusinessException(
                    "EXPENSE_VERSION_CONFLICT",
                    "Expense record was changed by another request. expenseId=" + id,
                    HttpStatus.CONFLICT);
        }
        return getExpense(id);
    }

    @Override
    @Transactional
    @AuditOperation(action = "VOID_EXPENSE", targetType = "EXPENSE_RECORD")
    public void deleteExpense(Long id, ExpenseDeleteRequest request) {
        ExpenseRecordEntity existing = findExpenseEntity(id);
        ensureExpenseCanBeChanged(existing);
        ensureVersionMatches(existing.getVersion(), request.version(), "EXPENSE_VERSION_CONFLICT");
        BudgetCategoryEntity budgetCategory = findBudgetCategoryEntity(existing.getProjectId(), existing.getBudgetCategoryId());

        applyBudgetUsedAmountDelta(budgetCategory, normalizeAmount(existing.getAmount(), "existing.amount").negate());

        OffsetDateTime now = OffsetDateTime.now();
        String currentUserId = CurrentUserContextHolder.currentOrAnonymous().userId();
        int updated = expenseRecordMapper.update(null, new LambdaUpdateWrapper<ExpenseRecordEntity>()
                .eq(ExpenseRecordEntity::getId, id)
                .eq(ExpenseRecordEntity::getVersion, request.version())
                .set(ExpenseRecordEntity::getStatus, EXPENSE_STATUS_VOIDED)
                .set(ExpenseRecordEntity::getDeleted, Integer.valueOf(1))
                .set(ExpenseRecordEntity::getDeletedAt, now)
                .set(ExpenseRecordEntity::getDeletedBy, currentUserId)
                .set(ExpenseRecordEntity::getDeleteReason, normalizeRequiredText(request.reason(), "reason"))
                .set(ExpenseRecordEntity::getUpdatedAt, now)
                .set(ExpenseRecordEntity::getUpdatedBy, currentUserId)
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw new BusinessException(
                    "EXPENSE_VERSION_CONFLICT",
                    "Expense record was changed by another request. expenseId=" + id,
                    HttpStatus.CONFLICT);
        }
    }

    private Map<Long, ResearchProjectEntity> projectsById(List<ExpenseRecordEntity> expenses) {
        Set<Long> ids = ids(expenses, ExpenseRecordEntity::getProjectId);
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return researchProjectMapper.selectBatchIds(ids)
                .stream()
                .collect(Collectors.toMap(ResearchProjectEntity::getId, Function.identity()));
    }

    private Map<Long, BudgetCategoryEntity> budgetCategoriesById(List<ExpenseRecordEntity> expenses) {
        Set<Long> ids = ids(expenses, ExpenseRecordEntity::getBudgetCategoryId);
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return budgetCategoryMapper.selectBatchIds(ids)
                .stream()
                .collect(Collectors.toMap(BudgetCategoryEntity::getId, Function.identity()));
    }

    private Set<Long> ids(List<ExpenseRecordEntity> expenses, Function<ExpenseRecordEntity, Long> idSelector) {
        return expenses.stream()
                .map(idSelector)
                .collect(Collectors.toSet());
    }

    private ExpenseRecordEntity findExpenseEntity(Long id) {
        ExpenseRecordEntity entity = expenseRecordMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(
                    "EXPENSE_NOT_FOUND",
                    "Expense record was not found. expenseId=" + id,
                    HttpStatus.NOT_FOUND);
        }
        return entity;
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

    private BudgetCategoryEntity findActiveBudgetCategoryEntity(Long projectId, Long id) {
        BudgetCategoryEntity entity = findBudgetCategoryEntity(projectId, id);
        if (!BUDGET_CATEGORY_STATUS_ACTIVE.equals(entity.getStatus())) {
            throw new BusinessException(
                    "BUDGET_CATEGORY_DISABLED",
                    "Budget category is not active. projectId=" + projectId + ", budgetCategoryId=" + id,
                    HttpStatus.CONFLICT);
        }
        return entity;
    }

    private void ensureProjectCanRecordExpense(ResearchProjectEntity project) {
        if (!PROJECT_STATUS_ACTIVE.equals(project.getStatus())) {
            throw new BusinessException(
                    "PROJECT_NOT_ACTIVE",
                    "Only active projects can record expenses. projectId=" + project.getId()
                            + ", status=" + project.getStatus(),
                    HttpStatus.CONFLICT);
        }
    }

    private void ensureExpenseCanBeChanged(ExpenseRecordEntity entity) {
        if (!EXPENSE_STATUS_REGISTERED.equals(entity.getStatus())) {
            throw new BusinessException(
                    "EXPENSE_STATUS_NOT_CHANGEABLE",
                    "Only registered expenses can be changed. expenseId=" + entity.getId()
                            + ", status=" + entity.getStatus(),
                    HttpStatus.CONFLICT);
        }
    }

    private void ensureBudgetCategoryHasAvailableAmount(
            BudgetCategoryEntity budgetCategory,
            BigDecimal requestedAmount,
            BigDecimal releasableAmount) {
        BigDecimal availableAmount = availableAmount(budgetCategory);
        BigDecimal effectiveAvailableAmount = MoneyUtils.add(availableAmount, releasableAmount);
        if (MoneyUtils.greaterThan(requestedAmount, effectiveAvailableAmount)) {
            throw new BusinessException(
                    "BUDGET_CATEGORY_AVAILABLE_AMOUNT_EXCEEDED",
                    "Expense amount exceeds budget category available amount. budgetCategoryId="
                            + budgetCategory.getId() + ", requestedAmount=" + requestedAmount
                            + ", availableAmount=" + effectiveAvailableAmount,
                    HttpStatus.CONFLICT);
        }
    }

    private void applyBudgetUsedAmountDelta(BudgetCategoryEntity budgetCategory, BigDecimal delta) {
        BigDecimal nextUsedAmount = MoneyUtils.add(normalizeAmount(budgetCategory.getUsedAmount(), "usedAmount"), delta);
        if (nextUsedAmount.signum() < 0) {
            throw new BusinessException(
                    "BUDGET_CATEGORY_USED_AMOUNT_NEGATIVE",
                    "Budget category usedAmount would become negative. budgetCategoryId="
                            + budgetCategory.getId() + ", currentUsedAmount=" + budgetCategory.getUsedAmount()
                            + ", delta=" + delta,
                    HttpStatus.CONFLICT);
        }

        BudgetCategoryEntity updateEntity = new BudgetCategoryEntity();
        updateEntity.setId(budgetCategory.getId());
        updateEntity.setUsedAmount(nextUsedAmount);
        updateEntity.setVersion(budgetCategory.getVersion());
        int updated = budgetCategoryMapper.updateById(updateEntity);
        if (updated != 1) {
            throw new BusinessException(
                    "BUDGET_CATEGORY_VERSION_CONFLICT",
                    "Budget category was changed by another request. budgetCategoryId=" + budgetCategory.getId(),
                    HttpStatus.CONFLICT);
        }
        budgetCategory.setUsedAmount(nextUsedAmount);
        budgetCategory.setVersion(budgetCategory.getVersion() + 1);
    }

    private BigDecimal availableAmount(BudgetCategoryEntity budgetCategory) {
        BigDecimal allocatedAmount = normalizeAmount(budgetCategory.getAllocatedAmount(), "allocatedAmount");
        BigDecimal usedAmount = normalizeAmount(budgetCategory.getUsedAmount(), "usedAmount");
        BigDecimal frozenAmount = normalizeAmount(budgetCategory.getFrozenAmount(), "frozenAmount");
        return MoneyUtils.subtract(MoneyUtils.subtract(allocatedAmount, usedAmount), frozenAmount);
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new BusinessException(
                    "EXPENSE_DATE_RANGE_INVALID",
                    "Expense endDate must not be before startDate. startDate=" + startDate + ", endDate=" + endDate);
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

    private BigDecimal normalizePositiveAmount(BigDecimal amount, String fieldName) {
        BigDecimal normalizedAmount = normalizeAmount(amount, fieldName);
        if (normalizedAmount.signum() <= 0) {
            throw new BusinessException("MONEY_AMOUNT_NOT_POSITIVE", fieldName + " must be greater than 0");
        }
        return normalizedAmount;
    }

    private BigDecimal normalizeAmount(BigDecimal amount, String fieldName) {
        if (amount == null) {
            throw new BusinessException("MONEY_AMOUNT_REQUIRED", fieldName + " must not be null");
        }
        return MoneyUtils.normalize(amount);
    }

    private BigDecimal zeroAmount() {
        return BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE, MoneyUtils.MONEY_ROUNDING_MODE);
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
