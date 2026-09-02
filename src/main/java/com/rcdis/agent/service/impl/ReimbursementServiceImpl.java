package com.rcdis.agent.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
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
import com.rcdis.agent.dto.QuickExpenseInput;
import com.rcdis.agent.dto.ReimbursementActionRequest;
import com.rcdis.agent.dto.ReimbursementCreateRequest;
import com.rcdis.agent.dto.ReimbursementPageRequest;
import com.rcdis.agent.dto.ReimbursementUpdateRequest;
import com.rcdis.agent.entity.BudgetCategoryEntity;
import com.rcdis.agent.entity.ExpenseRecordEntity;
import com.rcdis.agent.entity.ReimbursementItemEntity;
import com.rcdis.agent.entity.ReimbursementOrderEntity;
import com.rcdis.agent.entity.ResearchProjectEntity;
import com.rcdis.agent.mapper.BudgetCategoryMapper;
import com.rcdis.agent.mapper.ExpenseRecordMapper;
import com.rcdis.agent.mapper.ReimbursementItemMapper;
import com.rcdis.agent.mapper.ReimbursementOrderMapper;
import com.rcdis.agent.mapper.ResearchProjectMapper;
import com.rcdis.agent.service.ExpenseService;
import com.rcdis.agent.service.ReimbursementService;
import com.rcdis.agent.vo.ExpenseVO;
import com.rcdis.agent.vo.MaterialCheckVO;
import com.rcdis.agent.vo.ReimbursementDetailVO;
import com.rcdis.agent.vo.ReimbursementItemVO;
import com.rcdis.agent.vo.ReimbursementVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReimbursementServiceImpl implements ReimbursementService {

    private static final String ORDER_STATUS_DRAFT = "DRAFT";
    private static final String ORDER_STATUS_SUBMITTED = "SUBMITTED";
    private static final String ORDER_STATUS_APPROVED = "APPROVED";
    private static final String ORDER_STATUS_REJECTED = "REJECTED";
    private static final String ORDER_STATUS_VOID = "VOID";

    private static final String EXPENSE_STATUS_REGISTERED = "REGISTERED";
    private static final String EXPENSE_STATUS_REIMBURSED = "REIMBURSED";
    private static final String PROJECT_STATUS_ACTIVE = "ACTIVE";

    private static final String FINDING_MISSING = "missing";
    private static final String FINDING_WARNING = "warning";

    private static final int AVAILABLE_EXPENSE_LIMIT = 200;

    private static final String QUICK_EXPENSE_REASON = "报销快捷录入";

    private final ReimbursementOrderMapper reimbursementOrderMapper;
    private final ReimbursementItemMapper reimbursementItemMapper;
    private final ExpenseRecordMapper expenseRecordMapper;
    private final ResearchProjectMapper researchProjectMapper;
    private final BudgetCategoryMapper budgetCategoryMapper;
    private final ExpenseService expenseService;

    @Override
    public PageResponse<ReimbursementVO> pageReimbursements(ReimbursementPageRequest request) {
        validatePage(request.current(), request.size());

        Page<ReimbursementOrderEntity> page = new Page<>(request.current(), request.size());
        Page<ReimbursementOrderEntity> entityPage = reimbursementOrderMapper.selectReimbursementPage(
                page,
                request.projectId(),
                request.status(),
                StringUtils.trimWhitespace(request.keyword()));

        Map<Long, ResearchProjectEntity> projects = projectsById(entityPage.getRecords());
        Map<Long, Integer> itemCounts = itemCounts(entityPage.getRecords());

        Page<ReimbursementVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream()
                .map(order -> ReimbursementVO.fromEntity(
                        order,
                        projects.get(order.getProjectId()),
                        itemCounts.getOrDefault(order.getId(), 0)))
                .toList());
        return PageResponse.fromPage(voPage);
    }

    @Override
    public ReimbursementDetailVO getReimbursement(Long id) {
        ReimbursementOrderEntity order = findOrderEntity(id);
        ResearchProjectEntity project = researchProjectMapper.selectById(order.getProjectId());
        List<ReimbursementItemEntity> items = findItems(id);
        Map<Long, ExpenseRecordEntity> expenses = expensesById(items);
        Map<Long, BudgetCategoryEntity> categories = budgetCategoriesById(expenses.values());
        List<ReimbursementItemVO> itemVOs = items.stream()
                .map(item -> ReimbursementItemVO.from(
                        item,
                        expenses.get(item.getExpenseId()),
                        categoryOf(expenses.get(item.getExpenseId()), categories)))
                .toList();
        return new ReimbursementDetailVO(
                ReimbursementVO.fromEntity(order, project, items.size()),
                itemVOs);
    }

    @Override
    @Transactional
    @AuditOperation(action = "CREATE_REIMBURSEMENT", targetType = "REIMBURSEMENT_ORDER")
    public ReimbursementDetailVO createReimbursement(ReimbursementCreateRequest request) {
        ResearchProjectEntity project = findProjectEntity(request.projectId());
        ensureProjectIsActive(project);

        List<Long> selectedExpenseIds = request.expenseIds() == null
                ? List.of()
                : request.expenseIds();
        List<QuickExpenseInput> newExpenses = request.newExpenses() == null
                ? List.of()
                : request.newExpenses();
        if (selectedExpenseIds.isEmpty() && newExpenses.isEmpty()) {
            throw new BusinessException(
                    "REIMBURSEMENT_EXPENSES_REQUIRED",
                    "请至少选择一笔已登记支出或快捷录入一笔新支出");
        }

        // Register quick-entered expenses first through the ExpenseService proxy so that
        // budget validation, budget occupation and CREATE_EXPENSE audit are preserved.
        List<Long> quickExpenseIds = new ArrayList<>();
        for (QuickExpenseInput input : newExpenses) {
            ExpenseVO created = expenseService.createExpense(new ExpenseCreateRequest(
                    request.projectId(),
                    input.budgetCategoryId(),
                    input.amount(),
                    input.expenseDate(),
                    input.vendor(),
                    input.invoiceNo(),
                    input.receiptFile(),
                    input.description(),
                    QUICK_EXPENSE_REASON));
            quickExpenseIds.add(created.id());
        }

        List<Long> expenseIds = java.util.stream.Stream.concat(
                        selectedExpenseIds.stream(), quickExpenseIds.stream())
                .distinct()
                .toList();
        Map<Long, ExpenseRecordEntity> expensesById =
                validateLinkableExpenses(expenseIds, request.projectId(), null);

        BigDecimal totalAmount = zeroAmount();
        for (Long expenseId : expenseIds) {
            totalAmount = MoneyUtils.add(totalAmount, expensesById.get(expenseId).getAmount());
        }

        ReimbursementOrderEntity order = new ReimbursementOrderEntity();
        order.setReimbursementNo(nextReimbursementNo());
        order.setProjectId(request.projectId());
        order.setApplicant(request.applicant().trim());
        order.setTotalAmount(totalAmount);
        order.setStatus(ORDER_STATUS_DRAFT);
        order.setVersion(Integer.valueOf(0));
        reimbursementOrderMapper.insert(order);

        for (Long expenseId : expenseIds) {
            ReimbursementItemEntity item = new ReimbursementItemEntity();
            item.setReimbursementId(order.getId());
            item.setExpenseId(expenseId);
            item.setAmount(MoneyUtils.normalize(expensesById.get(expenseId).getAmount()));
            reimbursementItemMapper.insert(item);
        }

        log.atInfo()
                .addKeyValue("reimbursementId", order.getId())
                .addKeyValue("reimbursementNo", order.getReimbursementNo())
                .addKeyValue("projectId", request.projectId())
                .addKeyValue("itemCount", expenseIds.size())
                .addKeyValue("quickExpenseCount", quickExpenseIds.size())
                .addKeyValue("totalAmount", totalAmount)
                .log("Reimbursement order created");
        return getReimbursement(order.getId());
    }

    @Override
    public List<ExpenseVO> listAvailableExpenses(Long projectId, Long excludeOrderId) {
        findProjectEntity(projectId);
        List<ExpenseRecordEntity> expenses = expenseRecordMapper.selectList(
                new LambdaQueryWrapper<ExpenseRecordEntity>()
                        .eq(ExpenseRecordEntity::getProjectId, projectId)
                        .eq(ExpenseRecordEntity::getStatus, EXPENSE_STATUS_REGISTERED)
                        .orderByDesc(ExpenseRecordEntity::getExpenseDate)
                        .orderByDesc(ExpenseRecordEntity::getId)
                        .last("limit " + AVAILABLE_EXPENSE_LIMIT));
        Set<Long> linkedExpenseIds = linkedExpenseIds(
                expenses.stream().map(ExpenseRecordEntity::getId).toList(), excludeOrderId);
        ResearchProjectEntity project = researchProjectMapper.selectById(projectId);
        Map<Long, BudgetCategoryEntity> categories = budgetCategoriesById(expenses);
        return expenses.stream()
                .filter(expense -> !linkedExpenseIds.contains(expense.getId()))
                .map(expense -> ExpenseVO.fromEntity(
                        expense,
                        project,
                        categories.get(expense.getBudgetCategoryId())))
                .toList();
    }

    @Override
    public MaterialCheckVO checkMaterials(Long id) {
        ReimbursementOrderEntity order = findOrderEntity(id);
        List<ReimbursementItemEntity> items = findItems(id);
        Map<Long, ExpenseRecordEntity> expenses = expensesById(items);

        List<MaterialCheckVO.Finding> findings = new ArrayList<>();
        if (!StringUtils.hasText(order.getApplicant())) {
            findings.add(new MaterialCheckVO.Finding(FINDING_MISSING, null, "报销单", "缺少申请人"));
        }
        for (ReimbursementItemEntity item : items) {
            ExpenseRecordEntity expense = expenses.get(item.getExpenseId());
            String label = expense == null
                    ? "单据 #" + item.getExpenseId()
                    : "单据 #" + expense.getId() + " · " + expenseLabel(expense);
            if (expense == null) {
                findings.add(new MaterialCheckVO.Finding(FINDING_MISSING, item.getExpenseId(), label, "支出记录不存在"));
                continue;
            }
            if (!EXPENSE_STATUS_REGISTERED.equals(expense.getStatus())
                    && !EXPENSE_STATUS_REIMBURSED.equals(expense.getStatus())) {
                findings.add(new MaterialCheckVO.Finding(FINDING_MISSING, expense.getId(), label, "支出状态异常，无法报销"));
            }
            if (!StringUtils.hasText(expense.getInvoiceNo())
                    && !StringUtils.hasText(expense.getReceiptFile())) {
                findings.add(new MaterialCheckVO.Finding(
                        FINDING_MISSING, expense.getId(), label, "缺少发票号或发票/支付证明图片"));
            }
            if (!StringUtils.hasText(expense.getVendor())) {
                findings.add(new MaterialCheckVO.Finding(FINDING_MISSING, expense.getId(), label, "缺少供应商信息"));
            }
            if (!StringUtils.hasText(expense.getDescription())) {
                findings.add(new MaterialCheckVO.Finding(FINDING_MISSING, expense.getId(), label, "缺少用途说明"));
            }
            if (expense.getAmount() == null || expense.getAmount().signum() <= 0) {
                findings.add(new MaterialCheckVO.Finding(FINDING_WARNING, expense.getId(), label, "金额无效"));
            }
        }
        boolean pass = findings.stream().noneMatch(finding -> FINDING_MISSING.equals(finding.level()));
        return new MaterialCheckVO(pass, items.size(), List.copyOf(findings));
    }

    @Override
    @Transactional
    @AuditOperation(action = "UPDATE_REIMBURSEMENT", targetType = "REIMBURSEMENT_ORDER")
    public ReimbursementDetailVO updateReimbursement(Long id, ReimbursementUpdateRequest request) {
        ReimbursementOrderEntity order = findOrderEntity(id);
        if (!ORDER_STATUS_DRAFT.equals(order.getStatus())) {
            throw new BusinessException(
                    "REIMBURSEMENT_NOT_EDITABLE",
                    "只有草稿状态的报销单可以修改。reimbursementId=" + id + ", status=" + order.getStatus(),
                    HttpStatus.CONFLICT);
        }
        ResearchProjectEntity project = findProjectEntity(order.getProjectId());
        ensureProjectIsActive(project);

        List<Long> expenseIds = request.expenseIds().stream().distinct().toList();
        if (expenseIds.isEmpty()) {
            throw new BusinessException("EXPENSE_IDS_REQUIRED", "报销单至少需要关联一笔支出");
        }
        Map<Long, ExpenseRecordEntity> expensesById =
                validateLinkableExpenses(expenseIds, order.getProjectId(), id);

        List<ReimbursementItemEntity> currentItems = findItems(id);
        Set<Long> currentIds = currentItems.stream()
                .map(ReimbursementItemEntity::getExpenseId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> targetIds = new LinkedHashSet<>(expenseIds);

        List<Long> removedIds = currentIds.stream()
                .filter(expenseId -> !targetIds.contains(expenseId))
                .toList();
        List<Long> addedIds = expenseIds.stream()
                .filter(expenseId -> !currentIds.contains(expenseId))
                .toList();
        if (!removedIds.isEmpty()) {
            // Physical delete: released expenses must become linkable by other orders again.
            reimbursementItemMapper.physicalDeleteByReimbursementIdAndExpenseIds(id, removedIds);
        }
        BigDecimal totalAmount = zeroAmount();
        for (Long expenseId : addedIds) {
            ReimbursementItemEntity item = new ReimbursementItemEntity();
            item.setReimbursementId(id);
            item.setExpenseId(expenseId);
            item.setAmount(MoneyUtils.normalize(expensesById.get(expenseId).getAmount()));
            reimbursementItemMapper.insert(item);
        }
        for (Long expenseId : expenseIds) {
            totalAmount = MoneyUtils.add(totalAmount, expensesById.get(expenseId).getAmount());
        }

        OffsetDateTime now = OffsetDateTime.now();
        int updated = reimbursementOrderMapper.update(null, new LambdaUpdateWrapper<ReimbursementOrderEntity>()
                .eq(ReimbursementOrderEntity::getId, id)
                .eq(ReimbursementOrderEntity::getStatus, ORDER_STATUS_DRAFT)
                .eq(ReimbursementOrderEntity::getVersion, request.version())
                .set(ReimbursementOrderEntity::getApplicant, request.applicant().trim())
                .set(ReimbursementOrderEntity::getTotalAmount, totalAmount)
                .set(ReimbursementOrderEntity::getUpdatedAt, now)
                .set(ReimbursementOrderEntity::getUpdatedBy, CurrentUserContextHolder.currentOrAnonymous().userId())
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw new BusinessException(
                    "REIMBURSEMENT_STATUS_CONFLICT",
                    "报销单已被其他请求修改，请刷新后重试。reimbursementId=" + id,
                    HttpStatus.CONFLICT);
        }

        log.atInfo()
                .addKeyValue("reimbursementId", id)
                .addKeyValue("addedExpenseIds", addedIds)
                .addKeyValue("removedExpenseIds", removedIds)
                .addKeyValue("totalAmount", totalAmount)
                .log("Reimbursement draft updated");
        return getReimbursement(id);
    }

    @Override
    @Transactional
    @AuditOperation(action = "VOID_REIMBURSEMENT", targetType = "REIMBURSEMENT_ORDER")
    public ReimbursementDetailVO voidReimbursement(Long id, ReimbursementActionRequest request) {
        findOrderEntity(id);
        normalizeRequiredText(request == null ? null : request.reason(), "reason");

        List<Long> releasedExpenseIds = findItems(id).stream()
                .map(ReimbursementItemEntity::getExpenseId)
                .toList();

        OffsetDateTime now = OffsetDateTime.now();
        int updated = reimbursementOrderMapper.update(null, new LambdaUpdateWrapper<ReimbursementOrderEntity>()
                .eq(ReimbursementOrderEntity::getId, id)
                .in(ReimbursementOrderEntity::getStatus, List.of(ORDER_STATUS_DRAFT, ORDER_STATUS_REJECTED))
                .set(ReimbursementOrderEntity::getStatus, ORDER_STATUS_VOID)
                .set(ReimbursementOrderEntity::getUpdatedAt, now)
                .set(ReimbursementOrderEntity::getUpdatedBy, CurrentUserContextHolder.currentOrAnonymous().userId())
                .setSql("version = version + 1"));
        ensureTransitionApplied(updated, id);

        // Physical delete so the unique expense_id constraint is released and
        // the expenses become available for other reimbursement orders again.
        reimbursementItemMapper.physicalDeleteByReimbursementId(id);

        log.atInfo()
                .addKeyValue("reimbursementId", id)
                .addKeyValue("releasedExpenseIds", releasedExpenseIds)
                .log("Reimbursement order voided, linked expenses released");
        return getReimbursement(id);
    }

    @Override
    @Transactional
    @AuditOperation(action = "SUBMIT_REIMBURSEMENT", targetType = "REIMBURSEMENT_ORDER")
    public ReimbursementDetailVO submitReimbursement(Long id, ReimbursementActionRequest request) {
        findOrderEntity(id);
        MaterialCheckVO check = checkMaterials(id);
        if (!check.pass()) {
            long missingCount = check.findings().stream()
                    .filter(finding -> FINDING_MISSING.equals(finding.level()))
                    .count();
            throw new BusinessException(
                    "REIMBURSEMENT_MATERIALS_INCOMPLETE",
                    "材料检查未通过，提交被拒绝：共 " + missingCount
                            + " 项材料缺失，请在「支出管理」补齐后重新检查再提交",
                    HttpStatus.CONFLICT);
        }

        OffsetDateTime now = OffsetDateTime.now();
        int updated = reimbursementOrderMapper.update(null, new LambdaUpdateWrapper<ReimbursementOrderEntity>()
                .eq(ReimbursementOrderEntity::getId, id)
                .in(ReimbursementOrderEntity::getStatus, List.of(ORDER_STATUS_DRAFT, ORDER_STATUS_REJECTED))
                .set(ReimbursementOrderEntity::getStatus, ORDER_STATUS_SUBMITTED)
                .set(ReimbursementOrderEntity::getSubmittedAt, now)
                .set(ReimbursementOrderEntity::getUpdatedAt, now)
                .set(ReimbursementOrderEntity::getUpdatedBy, CurrentUserContextHolder.currentOrAnonymous().userId())
                .setSql("version = version + 1"));
        ensureTransitionApplied(updated, id);

        log.atInfo().addKeyValue("reimbursementId", id).log("Reimbursement order submitted");
        return getReimbursement(id);
    }

    @Override
    @Transactional
    @AuditOperation(action = "APPROVE_REIMBURSEMENT", targetType = "REIMBURSEMENT_ORDER")
    public ReimbursementDetailVO approveReimbursement(Long id, ReimbursementActionRequest request) {
        findOrderEntity(id);
        OffsetDateTime now = OffsetDateTime.now();
        int updated = reimbursementOrderMapper.update(null, new LambdaUpdateWrapper<ReimbursementOrderEntity>()
                .eq(ReimbursementOrderEntity::getId, id)
                .eq(ReimbursementOrderEntity::getStatus, ORDER_STATUS_SUBMITTED)
                .set(ReimbursementOrderEntity::getStatus, ORDER_STATUS_APPROVED)
                .set(ReimbursementOrderEntity::getApprovedAt, now)
                .set(ReimbursementOrderEntity::getUpdatedAt, now)
                .set(ReimbursementOrderEntity::getUpdatedBy, CurrentUserContextHolder.currentOrAnonymous().userId())
                .setSql("version = version + 1"));
        ensureTransitionApplied(updated, id);

        // Budget was already occupied when expenses were registered;
        // approval only marks the linked expenses as reimbursed.
        updateLinkedExpenseStatuses(id, EXPENSE_STATUS_REGISTERED, EXPENSE_STATUS_REIMBURSED);

        log.atInfo().addKeyValue("reimbursementId", id).log("Reimbursement order approved");
        return getReimbursement(id);
    }

    @Override
    @Transactional
    @AuditOperation(action = "REJECT_REIMBURSEMENT", targetType = "REIMBURSEMENT_ORDER")
    public ReimbursementDetailVO rejectReimbursement(Long id, ReimbursementActionRequest request) {
        findOrderEntity(id);
        String rejectReason = normalizeRequiredText(request == null ? null : request.reason(), "reason");

        OffsetDateTime now = OffsetDateTime.now();
        int updated = reimbursementOrderMapper.update(null, new LambdaUpdateWrapper<ReimbursementOrderEntity>()
                .eq(ReimbursementOrderEntity::getId, id)
                .eq(ReimbursementOrderEntity::getStatus, ORDER_STATUS_SUBMITTED)
                .set(ReimbursementOrderEntity::getStatus, ORDER_STATUS_REJECTED)
                .set(ReimbursementOrderEntity::getRejectReason, rejectReason)
                .set(ReimbursementOrderEntity::getUpdatedAt, now)
                .set(ReimbursementOrderEntity::getUpdatedBy, CurrentUserContextHolder.currentOrAnonymous().userId())
                .setSql("version = version + 1"));
        ensureTransitionApplied(updated, id);

        // Linked expenses stay REGISTERED while the order is SUBMITTED,
        // so rejection only flips the order status; expenses need no change.

        log.atInfo().addKeyValue("reimbursementId", id).log("Reimbursement order rejected");
        return getReimbursement(id);
    }

    // ---------- helpers ----------

    private ReimbursementOrderEntity findOrderEntity(Long id) {
        ReimbursementOrderEntity order = reimbursementOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(
                    "REIMBURSEMENT_NOT_FOUND",
                    "Reimbursement order was not found. reimbursementId=" + id,
                    HttpStatus.NOT_FOUND);
        }
        return order;
    }

    private List<ReimbursementItemEntity> findItems(Long reimbursementId) {
        return reimbursementItemMapper.selectList(new LambdaQueryWrapper<ReimbursementItemEntity>()
                .eq(ReimbursementItemEntity::getReimbursementId, reimbursementId)
                .orderByAsc(ReimbursementItemEntity::getId));
    }

    private Map<Long, ExpenseRecordEntity> expensesById(List<ReimbursementItemEntity> items) {
        if (items.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> expenseIds = items.stream()
                .map(ReimbursementItemEntity::getExpenseId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return expenseRecordMapper.selectBatchIds(expenseIds).stream()
                .collect(Collectors.toMap(ExpenseRecordEntity::getId, Function.identity()));
    }

    private Map<Long, BudgetCategoryEntity> budgetCategoriesById(
            java.util.Collection<ExpenseRecordEntity> expenses) {
        Set<Long> categoryIds = expenses.stream()
                .map(ExpenseRecordEntity::getBudgetCategoryId)
                .collect(Collectors.toSet());
        if (categoryIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return budgetCategoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(BudgetCategoryEntity::getId, Function.identity()));
    }

    private BudgetCategoryEntity categoryOf(
            ExpenseRecordEntity expense,
            Map<Long, BudgetCategoryEntity> categories) {
        return expense == null ? null : categories.get(expense.getBudgetCategoryId());
    }

    private Map<Long, ResearchProjectEntity> projectsById(List<ReimbursementOrderEntity> orders) {
        Set<Long> projectIds = orders.stream()
                .map(ReimbursementOrderEntity::getProjectId)
                .collect(Collectors.toSet());
        if (projectIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return researchProjectMapper.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(ResearchProjectEntity::getId, Function.identity()));
    }

    private Map<Long, Integer> itemCounts(List<ReimbursementOrderEntity> orders) {
        if (orders.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> orderIds = orders.stream().map(ReimbursementOrderEntity::getId).toList();
        List<ReimbursementItemEntity> items = reimbursementItemMapper.selectList(
                new LambdaQueryWrapper<ReimbursementItemEntity>()
                        .in(ReimbursementItemEntity::getReimbursementId, orderIds));
        return items.stream().collect(Collectors.groupingBy(
                ReimbursementItemEntity::getReimbursementId,
                Collectors.summingInt(item -> 1)));
    }

    private Set<Long> linkedExpenseIds(List<Long> expenseIds, Long excludeOrderId) {
        if (expenseIds.isEmpty()) {
            return Collections.emptySet();
        }
        LambdaQueryWrapper<ReimbursementItemEntity> wrapper = new LambdaQueryWrapper<ReimbursementItemEntity>()
                .in(ReimbursementItemEntity::getExpenseId, expenseIds);
        if (excludeOrderId != null) {
            wrapper.ne(ReimbursementItemEntity::getReimbursementId, excludeOrderId);
        }
        return reimbursementItemMapper.selectList(wrapper).stream()
                .map(ReimbursementItemEntity::getExpenseId)
                .collect(Collectors.toSet());
    }

    /**
     * Validate that every expense exists, belongs to the project, is REGISTERED and
     * is not linked to another order (items of excludeOrderId are ignored).
     */
    private Map<Long, ExpenseRecordEntity> validateLinkableExpenses(
            List<Long> expenseIds, Long projectId, Long excludeOrderId) {
        if (expenseIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, ExpenseRecordEntity> expensesById = expenseRecordMapper.selectBatchIds(expenseIds).stream()
                .collect(Collectors.toMap(ExpenseRecordEntity::getId, Function.identity()));
        for (Long expenseId : expenseIds) {
            ExpenseRecordEntity expense = expensesById.get(expenseId);
            if (expense == null) {
                throw new BusinessException(
                        "EXPENSE_NOT_FOUND",
                        "Expense record was not found. expenseId=" + expenseId,
                        HttpStatus.NOT_FOUND);
            }
            if (!expense.getProjectId().equals(projectId)) {
                throw new BusinessException(
                        "EXPENSE_PROJECT_MISMATCH",
                        "Expense does not belong to the reimbursement project. expenseId=" + expenseId
                                + ", expenseProjectId=" + expense.getProjectId()
                                + ", projectId=" + projectId,
                        HttpStatus.BAD_REQUEST);
            }
            if (!EXPENSE_STATUS_REGISTERED.equals(expense.getStatus())) {
                throw new BusinessException(
                        "EXPENSE_NOT_REIMBURSABLE",
                        "Only registered expenses can be reimbursed. expenseId=" + expenseId
                                + ", status=" + expense.getStatus(),
                        HttpStatus.CONFLICT);
            }
        }
        Set<Long> linkedIds = linkedExpenseIds(expenseIds, excludeOrderId);
        if (!linkedIds.isEmpty()) {
            throw new BusinessException(
                    "EXPENSE_ALREADY_LINKED",
                    "Expenses are already linked to another reimbursement order. expenseIds=" + linkedIds,
                    HttpStatus.CONFLICT);
        }
        return expensesById;
    }

    private void updateLinkedExpenseStatuses(Long reimbursementId, String expectedStatus, String targetStatus) {
        List<Long> expenseIds = findItems(reimbursementId).stream()
                .map(ReimbursementItemEntity::getExpenseId)
                .toList();
        if (expenseIds.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        int updated = expenseRecordMapper.update(null, new LambdaUpdateWrapper<ExpenseRecordEntity>()
                .in(ExpenseRecordEntity::getId, expenseIds)
                .eq(ExpenseRecordEntity::getStatus, expectedStatus)
                .set(ExpenseRecordEntity::getStatus, targetStatus)
                .set(ExpenseRecordEntity::getUpdatedAt, now)
                .set(ExpenseRecordEntity::getUpdatedBy, CurrentUserContextHolder.currentOrAnonymous().userId())
                .setSql("version = version + 1"));
        if (updated != expenseIds.size()) {
            throw new BusinessException(
                    "EXPENSE_STATUS_CONFLICT",
                    "Linked expenses changed meanwhile, transition aborted. reimbursementId=" + reimbursementId
                            + ", expectedUpdates=" + expenseIds.size() + ", actualUpdates=" + updated,
                    HttpStatus.CONFLICT);
        }
    }

    private void ensureTransitionApplied(int updated, Long id) {
        if (updated != 1) {
            throw new BusinessException(
                    "REIMBURSEMENT_STATUS_CONFLICT",
                    "Reimbursement order status was changed by another request. reimbursementId=" + id,
                    HttpStatus.CONFLICT);
        }
    }

    private String nextReimbursementNo() {
        Long sequence = reimbursementOrderMapper.nextReimbursementNoSequence();
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        return "R-" + date + "-" + String.format("%04d", sequence);
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

    private void ensureProjectIsActive(ResearchProjectEntity project) {
        if (!PROJECT_STATUS_ACTIVE.equals(project.getStatus())) {
            throw new BusinessException(
                    "PROJECT_NOT_ACTIVE",
                    "Only active projects can create reimbursements. projectId=" + project.getId()
                            + ", status=" + project.getStatus(),
                    HttpStatus.CONFLICT);
        }
    }

    private String expenseLabel(ExpenseRecordEntity expense) {
        if (StringUtils.hasText(expense.getVendor())) {
            return expense.getVendor();
        }
        if (StringUtils.hasText(expense.getDescription())) {
            return expense.getDescription();
        }
        return "未填写事项";
    }

    private void validatePage(long current, long size) {
        if (current < 1) {
            throw new BusinessException("PAGE_CURRENT_INVALID", "Page current must be greater than 0");
        }
        if (size < 1 || size > 500) {
            throw new BusinessException("PAGE_SIZE_INVALID", "Page size must be between 1 and 500");
        }
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
}
