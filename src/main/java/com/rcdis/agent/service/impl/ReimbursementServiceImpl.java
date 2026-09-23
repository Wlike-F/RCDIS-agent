package com.rcdis.agent.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.common.util.MoneyUtils;
import com.rcdis.agent.dto.ReimbursementActionRequest;
import com.rcdis.agent.dto.ReimbursementCreateRequest;
import com.rcdis.agent.dto.ReimbursementItemInput;
import com.rcdis.agent.dto.ReimbursementPageRequest;
import com.rcdis.agent.dto.ReimbursementUpdateRequest;
import com.rcdis.agent.entity.ReimbursementItemEntity;
import com.rcdis.agent.entity.ReimbursementOrderEntity;
import com.rcdis.agent.entity.ResearchProjectEntity;
import com.rcdis.agent.mapper.ReimbursementItemMapper;
import com.rcdis.agent.mapper.ReimbursementOrderMapper;
import com.rcdis.agent.mapper.ResearchProjectMapper;
import com.rcdis.agent.service.BudgetOccupationService;
import com.rcdis.agent.service.ReimbursementService;
import com.rcdis.agent.service.FileStorageService;
import com.rcdis.agent.service.ReceiptOcrService;
import com.rcdis.agent.vo.MaterialCheckVO;
import com.rcdis.agent.vo.ReimbursementDetailVO;
import com.rcdis.agent.vo.ReimbursementItemVO;
import com.rcdis.agent.vo.ReimbursementVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reimbursement order workflow. An order is the single carrier of spend facts: its line
 * items hold amount / date / vendor / invoice / receipt / description directly, and project
 * budget is occupied through {@link BudgetOccupationService} according to the order status.
 *
 * <p>Two payment types share this state machine: {@code reimbursement} runs the full
 * approval flow (freeze at submit, consume at approve), while {@code public_payment} books
 * budget directly at submit (charge) with no approval step.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReimbursementServiceImpl implements ReimbursementService {

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_SUBMITTED = "submitted";
    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_REJECTED = "rejected";
    private static final String STATUS_VOID = "void";

    private static final String PAYMENT_REIMBURSEMENT = "reimbursement";
    private static final String PAYMENT_PUBLIC = "public_payment";

    private static final String PROJECT_STATUS_ACTIVE = "ACTIVE";

    private static final String FINDING_MISSING = "missing";
    private static final String FINDING_WARNING = "warning";

    private final ReimbursementOrderMapper reimbursementOrderMapper;
    private final ReimbursementItemMapper reimbursementItemMapper;
    private final ResearchProjectMapper researchProjectMapper;
    private final BudgetOccupationService budgetOccupationService;
    private final FileStorageService fileStorageService;
    private final ReceiptOcrService receiptOcrService;

    @Override
    public PageResponse<ReimbursementVO> pageReimbursements(ReimbursementPageRequest request) {
        validatePage(request.current(), request.size());

        Page<ReimbursementOrderEntity> page = new Page<>(request.current(), request.size());
        CurrentUserTO currentUser = CurrentUserContextHolder.currentOrAnonymous();
        String applicantScope = currentUser.hasRole("ADMIN") || currentUser.hasRole("APPROVER")
                ? null : currentUser.username();
        Page<ReimbursementOrderEntity> entityPage = reimbursementOrderMapper.selectReimbursementPage(
                page,
                request.projectId(),
                request.status(),
                StringUtils.trimWhitespace(request.keyword()),
                applicantScope);

        Map<Long, ResearchProjectEntity> projects = projectsById(entityPage.getRecords());
        Map<Long, List<ReimbursementItemEntity>> itemsByOrder = itemsByOrder(entityPage.getRecords());

        Page<ReimbursementVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream()
                .map(order -> {
                    List<ReimbursementItemEntity> orderItems =
                            itemsByOrder.getOrDefault(order.getId(), List.of());
                    return ReimbursementVO.fromEntity(
                            order,
                            projects.get(order.getProjectId()),
                            orderItems.size(),
                            summarizeProof(orderItems),
                            receiptFilesOf(orderItems));
                })
                .toList());
        return PageResponse.fromPage(voPage);
    }

    @Override
    public ReimbursementDetailVO getReimbursement(Long id) {
        ReimbursementOrderEntity order = findOrderEntity(id);
        ensureReadable(order);
        ResearchProjectEntity project = researchProjectMapper.selectById(order.getProjectId());
        List<ReimbursementItemEntity> items = findItems(id);
        List<ReimbursementItemVO> itemVOs = items.stream().map(ReimbursementItemVO::from).toList();
        return new ReimbursementDetailVO(
                ReimbursementVO.fromEntity(order, project, items.size(), summarizeProof(items), receiptFilesOf(items)),
                itemVOs);
    }

    @Override
    @Transactional
    @AuditOperation(action = "CREATE_REIMBURSEMENT", targetType = "REIMBURSEMENT_ORDER")
    public ReimbursementDetailVO createReimbursement(ReimbursementCreateRequest request) {
        CurrentUserTO current = CurrentUserContextHolder.currentOrAnonymous();
        requireAnyRole(current, "ADMIN", "RESEARCHER");
        ResearchProjectEntity project = findProjectEntity(request.projectId());
        ensureProjectIsActive(project);
        String paymentType = normalizePaymentType(request.paymentType());
        List<ReimbursementItemInput> inputs = request.items();
        if (inputs == null || inputs.isEmpty()) {
            throw new BusinessException("REIMBURSEMENT_ITEMS_REQUIRED", "报销单至少需要一条明细");
        }

        BigDecimal totalAmount = zeroAmount();
        for (ReimbursementItemInput input : inputs) {
            validateReceiptReference(input);
            totalAmount = MoneyUtils.add(totalAmount, input.amount());
        }

        ReimbursementOrderEntity order = new ReimbursementOrderEntity();
        order.setReimbursementNo(nextReimbursementNo());
        order.setProjectId(request.projectId());
        // Researchers cannot forge ownership. Administrators retain the explicit on-behalf-of
        // workflow used for importing and support operations; it remains audited.
        order.setApplicant(current.hasRole("ADMIN") ? request.applicant().trim() : current.username());
        order.setTotalAmount(MoneyUtils.normalize(totalAmount));
        order.setStatus(STATUS_DRAFT);
        order.setPaymentType(paymentType);
        order.setVersion(Integer.valueOf(0));
        reimbursementOrderMapper.insert(order);

        for (ReimbursementItemInput input : inputs) {
            reimbursementItemMapper.insert(toItemEntity(order.getId(), input));
        }

        log.atInfo()
                .addKeyValue("reimbursementId", order.getId())
                .addKeyValue("reimbursementNo", order.getReimbursementNo())
                .addKeyValue("projectId", request.projectId())
                .addKeyValue("paymentType", paymentType)
                .addKeyValue("itemCount", inputs.size())
                .addKeyValue("totalAmount", totalAmount)
                .log("Reimbursement order created");
        return getReimbursement(order.getId());
    }

    @Override
    @Transactional
    @AuditOperation(action = "UPDATE_REIMBURSEMENT", targetType = "REIMBURSEMENT_ORDER")
    public ReimbursementDetailVO updateReimbursement(Long id, ReimbursementUpdateRequest request) {
        ReimbursementOrderEntity order = findOrderEntity(id);
        ensureOwnerOrAdmin(order);
        if (!STATUS_DRAFT.equals(order.getStatus()) && !STATUS_REJECTED.equals(order.getStatus())) {
            throw new BusinessException(
                    "REIMBURSEMENT_NOT_EDITABLE",
                    "Only draft or rejected orders can be edited. reimbursementId=" + id + ", status=" + order.getStatus(),
                    HttpStatus.CONFLICT);
        }
        ensureProjectIsActive(findProjectEntity(order.getProjectId()));

        List<ReimbursementItemInput> inputs = request.items();
        if (inputs == null || inputs.isEmpty()) {
            throw new BusinessException("REIMBURSEMENT_ITEMS_REQUIRED", "报销单至少需要一条明细");
        }
        BigDecimal totalAmount = zeroAmount();
        for (ReimbursementItemInput input : inputs) {
            validateReceiptReference(input);
            totalAmount = MoneyUtils.add(totalAmount, input.amount());
        }

        // Full line replacement: draft and rejected orders hold no budget (reject releases the
        // frozen amount), so removed lines need no release.
        reimbursementItemMapper.physicalDeleteByReimbursementId(id);
        for (ReimbursementItemInput input : inputs) {
            reimbursementItemMapper.insert(toItemEntity(id, input));
        }

        OffsetDateTime now = OffsetDateTime.now();
        int updated = reimbursementOrderMapper.update(null, new LambdaUpdateWrapper<ReimbursementOrderEntity>()
                .eq(ReimbursementOrderEntity::getId, id)
                // CAS must pin the same editable states the guard above accepted, otherwise editing a
                // rejected order would always look like a concurrent modification.
                .in(ReimbursementOrderEntity::getStatus, STATUS_DRAFT, STATUS_REJECTED)
                .eq(ReimbursementOrderEntity::getVersion, request.version())
                .set(ReimbursementOrderEntity::getApplicant,
                        CurrentUserContextHolder.currentOrAnonymous().hasRole("ADMIN")
                                ? request.applicant().trim() : order.getApplicant())
                .set(ReimbursementOrderEntity::getTotalAmount, MoneyUtils.normalize(totalAmount))
                .set(ReimbursementOrderEntity::getUpdatedAt, now)
                .set(ReimbursementOrderEntity::getUpdatedBy, CurrentUserContextHolder.currentOrAnonymous().userId())
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw new BusinessException(
                    "REIMBURSEMENT_STATUS_CONFLICT",
                    "Reimbursement order was changed by another request. reimbursementId=" + id,
                    HttpStatus.CONFLICT);
        }

        log.atInfo()
                .addKeyValue("reimbursementId", id)
                .addKeyValue("itemCount", inputs.size())
                .addKeyValue("totalAmount", totalAmount)
                .log("Reimbursement draft updated");
        return getReimbursement(id);
    }

    @Override
    public MaterialCheckVO checkMaterials(Long id) {
        ReimbursementOrderEntity order = findOrderEntity(id);
        ensureReadable(order);
        List<ReimbursementItemEntity> items = findItems(id);
        boolean publicPayment = PAYMENT_PUBLIC.equals(order.getPaymentType());

        List<MaterialCheckVO.Finding> findings = new ArrayList<>();
        if (!StringUtils.hasText(order.getApplicant())) {
            findings.add(new MaterialCheckVO.Finding(FINDING_MISSING, null, "报销单", "缺少申请人"));
        }
        for (ReimbursementItemEntity item : items) {
            String label = "明细 #" + item.getId();
            if (item.getAmount() == null || item.getAmount().signum() <= 0) {
                findings.add(new MaterialCheckVO.Finding(FINDING_WARNING, item.getId(), label, "金额无效"));
            }
            if (!StringUtils.hasText(item.getDescription())) {
                findings.add(new MaterialCheckVO.Finding(FINDING_MISSING, item.getId(), label, "缺少用途说明"));
            }
            if (publicPayment) {
                if (!StringUtils.hasText(item.getCounterpartyAccount())) {
                    findings.add(new MaterialCheckVO.Finding(
                            FINDING_MISSING, item.getId(), label, "公卡支付缺少对方账户"));
                }
            } else {
                if (!StringUtils.hasText(item.getInvoiceNo()) && !StringUtils.hasText(item.getReceiptFile())) {
                    findings.add(new MaterialCheckVO.Finding(
                            FINDING_MISSING, item.getId(), label, "缺少发票号或发票/支付凭证"));
                }
                if (!StringUtils.hasText(item.getVendor())) {
                    findings.add(new MaterialCheckVO.Finding(FINDING_MISSING, item.getId(), label, "缺少供应商信息"));
                }
            }
            // OCR cross-check: recognized fields contradicting the item produce warnings only;
            // pass stays driven by MISSING findings (warnings do not block submission).
            findings.addAll(receiptOcrService.compareWithItem(item));
        }
        boolean pass = findings.stream().noneMatch(finding -> FINDING_MISSING.equals(finding.level()));
        return new MaterialCheckVO(pass, items.size(), List.copyOf(findings));
    }

    @Override
    @Transactional
    @AuditOperation(action = "SUBMIT_REIMBURSEMENT", targetType = "REIMBURSEMENT_ORDER")
    public ReimbursementDetailVO submitReimbursement(Long id, ReimbursementActionRequest request) {
        ReimbursementOrderEntity order = findOrderEntity(id);
        ensureOwnerOrAdmin(order);
        MaterialCheckVO check = checkMaterials(id);
        if (!check.pass()) {
            long missingCount = check.findings().stream()
                    .filter(finding -> FINDING_MISSING.equals(finding.level()))
                    .count();
            throw new BusinessException(
                    "REIMBURSEMENT_MATERIALS_INCOMPLETE",
                    "材料检查未通过，提交被拒绝：共 " + missingCount + " 项材料缺失，请补齐后重新提交",
                    HttpStatus.CONFLICT);
        }

        OffsetDateTime now = OffsetDateTime.now();
        String currentUserId = CurrentUserContextHolder.currentOrAnonymous().userId();
        if (PAYMENT_PUBLIC.equals(order.getPaymentType())) {
            // Public payment books directly: draft -> approved, charge used budget.
            int updated = reimbursementOrderMapper.update(null, new LambdaUpdateWrapper<ReimbursementOrderEntity>()
                    .eq(ReimbursementOrderEntity::getId, id)
                    .eq(ReimbursementOrderEntity::getStatus, STATUS_DRAFT)
                    .set(ReimbursementOrderEntity::getStatus, STATUS_APPROVED)
                    .set(ReimbursementOrderEntity::getSubmittedAt, now)
                    .set(ReimbursementOrderEntity::getApprovedAt, now)
                    .set(ReimbursementOrderEntity::getUpdatedAt, now)
                    .set(ReimbursementOrderEntity::getUpdatedBy, currentUserId)
                    .setSql("version = version + 1"));
            ensureTransitionApplied(updated, id);
            budgetOccupationService.charge(order.getProjectId(), order.getTotalAmount());
            log.atInfo().addKeyValue("reimbursementId", id).log("Public payment order booked at submit");
        } else {
            // Reimbursement: draft/rejected -> submitted, freeze budget pending approval.
            int updated = reimbursementOrderMapper.update(null, new LambdaUpdateWrapper<ReimbursementOrderEntity>()
                    .eq(ReimbursementOrderEntity::getId, id)
                    .in(ReimbursementOrderEntity::getStatus, List.of(STATUS_DRAFT, STATUS_REJECTED))
                    .set(ReimbursementOrderEntity::getStatus, STATUS_SUBMITTED)
                    .set(ReimbursementOrderEntity::getSubmittedAt, now)
                    .set(ReimbursementOrderEntity::getUpdatedAt, now)
                    .set(ReimbursementOrderEntity::getUpdatedBy, currentUserId)
                    .setSql("version = version + 1"));
            ensureTransitionApplied(updated, id);
            budgetOccupationService.freeze(order.getProjectId(), order.getTotalAmount());
            log.atInfo().addKeyValue("reimbursementId", id).log("Reimbursement order submitted, budget frozen");
        }
        return getReimbursement(id);
    }

    @Override
    @Transactional
    @AuditOperation(action = "WITHDRAW_REIMBURSEMENT", targetType = "REIMBURSEMENT_ORDER")
    public ReimbursementDetailVO withdrawReimbursement(Long id, ReimbursementActionRequest request) {
        ReimbursementOrderEntity order = findOrderEntity(id);
        CurrentUserTO current = CurrentUserContextHolder.currentOrAnonymous();
        boolean isAdmin = current.hasRole("ADMIN");
        boolean isApplicant = order.getApplicant() != null
                && (order.getApplicant().equals(current.username()) || order.getApplicant().equals(current.userId()));
        if (!isAdmin && !isApplicant) {
            throw new BusinessException(
                    "REIMBURSEMENT_WITHDRAW_FORBIDDEN",
                    "仅申请人本人或系统管理员可撤回该报销单",
                    HttpStatus.FORBIDDEN);
        }

        OffsetDateTime now = OffsetDateTime.now();
        int updated = reimbursementOrderMapper.update(null, new LambdaUpdateWrapper<ReimbursementOrderEntity>()
                .eq(ReimbursementOrderEntity::getId, id)
                .eq(ReimbursementOrderEntity::getStatus, STATUS_SUBMITTED)
                .set(ReimbursementOrderEntity::getStatus, STATUS_DRAFT)
                .set(ReimbursementOrderEntity::getSubmittedAt, null)
                .set(ReimbursementOrderEntity::getUpdatedAt, now)
                .set(ReimbursementOrderEntity::getUpdatedBy, current.userId())
                .setSql("version = version + 1"));
        ensureTransitionApplied(updated, id);
        budgetOccupationService.release(order.getProjectId(), order.getTotalAmount());

        log.atInfo().addKeyValue("reimbursementId", id).log("Reimbursement order withdrawn to draft, budget released");
        return getReimbursement(id);
    }

    @Override
    @Transactional
    @AuditOperation(action = "APPROVE_REIMBURSEMENT", targetType = "REIMBURSEMENT_ORDER")
    public ReimbursementDetailVO approveReimbursement(Long id, ReimbursementActionRequest request) {
        ReimbursementOrderEntity order = findOrderEntity(id);
        requireAnyRole(CurrentUserContextHolder.currentOrAnonymous(), "ADMIN", "APPROVER");
        if (PAYMENT_PUBLIC.equals(order.getPaymentType())) {
            throw new BusinessException(
                    "REIMBURSEMENT_NOT_APPROVABLE",
                    "公卡支付单提交即入账，无需审批。reimbursementId=" + id,
                    HttpStatus.BAD_REQUEST);
        }
        OffsetDateTime now = OffsetDateTime.now();
        int updated = reimbursementOrderMapper.update(null, new LambdaUpdateWrapper<ReimbursementOrderEntity>()
                .eq(ReimbursementOrderEntity::getId, id)
                .eq(ReimbursementOrderEntity::getStatus, STATUS_SUBMITTED)
                .set(ReimbursementOrderEntity::getStatus, STATUS_APPROVED)
                .set(ReimbursementOrderEntity::getApprovedAt, now)
                .set(ReimbursementOrderEntity::getUpdatedAt, now)
                .set(ReimbursementOrderEntity::getUpdatedBy, CurrentUserContextHolder.currentOrAnonymous().userId())
                .setSql("version = version + 1"));
        ensureTransitionApplied(updated, id);
        budgetOccupationService.consume(order.getProjectId(), order.getTotalAmount());

        log.atInfo().addKeyValue("reimbursementId", id).log("Reimbursement order approved, frozen budget consumed");
        return getReimbursement(id);
    }

    @Override
    @Transactional
    @AuditOperation(action = "REJECT_REIMBURSEMENT", targetType = "REIMBURSEMENT_ORDER")
    public ReimbursementDetailVO rejectReimbursement(Long id, ReimbursementActionRequest request) {
        ReimbursementOrderEntity order = findOrderEntity(id);
        requireAnyRole(CurrentUserContextHolder.currentOrAnonymous(), "ADMIN", "APPROVER");
        if (PAYMENT_PUBLIC.equals(order.getPaymentType())) {
            throw new BusinessException(
                    "REIMBURSEMENT_NOT_APPROVABLE",
                    "公卡支付单提交即入账，无驳回操作。reimbursementId=" + id,
                    HttpStatus.BAD_REQUEST);
        }
        String rejectReason = normalizeRequiredText(request == null ? null : request.reason(), "reason");

        OffsetDateTime now = OffsetDateTime.now();
        int updated = reimbursementOrderMapper.update(null, new LambdaUpdateWrapper<ReimbursementOrderEntity>()
                .eq(ReimbursementOrderEntity::getId, id)
                .eq(ReimbursementOrderEntity::getStatus, STATUS_SUBMITTED)
                .set(ReimbursementOrderEntity::getStatus, STATUS_REJECTED)
                .set(ReimbursementOrderEntity::getRejectReason, rejectReason)
                .set(ReimbursementOrderEntity::getUpdatedAt, now)
                .set(ReimbursementOrderEntity::getUpdatedBy, CurrentUserContextHolder.currentOrAnonymous().userId())
                .setSql("version = version + 1"));
        ensureTransitionApplied(updated, id);
        budgetOccupationService.release(order.getProjectId(), order.getTotalAmount());

        log.atInfo().addKeyValue("reimbursementId", id).log("Reimbursement order rejected, budget released");
        return getReimbursement(id);
    }

    @Override
    @Transactional
    @AuditOperation(action = "VOID_REIMBURSEMENT", targetType = "REIMBURSEMENT_ORDER")
    public ReimbursementDetailVO voidReimbursement(Long id, ReimbursementActionRequest request) {
        ReimbursementOrderEntity order = findOrderEntity(id);
        ensureOwnerOrAdmin(order);
        normalizeRequiredText(request == null ? null : request.reason(), "reason");

        OffsetDateTime now = OffsetDateTime.now();
        String currentUserId = CurrentUserContextHolder.currentOrAnonymous().userId();
        if (PAYMENT_PUBLIC.equals(order.getPaymentType())) {
            if (STATUS_APPROVED.equals(order.getStatus())) {
                // Void a booked public payment: reverse the used budget (冲销).
                int updated = reimbursementOrderMapper.update(null, new LambdaUpdateWrapper<ReimbursementOrderEntity>()
                        .eq(ReimbursementOrderEntity::getId, id)
                        .eq(ReimbursementOrderEntity::getStatus, STATUS_APPROVED)
                        .set(ReimbursementOrderEntity::getStatus, STATUS_VOID)
                        .set(ReimbursementOrderEntity::getUpdatedAt, now)
                        .set(ReimbursementOrderEntity::getUpdatedBy, currentUserId)
                        .setSql("version = version + 1"));
                ensureTransitionApplied(updated, id);
                budgetOccupationService.refundUsed(order.getProjectId(), order.getTotalAmount());
            } else {
                int updated = reimbursementOrderMapper.update(null, new LambdaUpdateWrapper<ReimbursementOrderEntity>()
                        .eq(ReimbursementOrderEntity::getId, id)
                        .eq(ReimbursementOrderEntity::getStatus, STATUS_DRAFT)
                        .set(ReimbursementOrderEntity::getStatus, STATUS_VOID)
                        .set(ReimbursementOrderEntity::getUpdatedAt, now)
                        .set(ReimbursementOrderEntity::getUpdatedBy, currentUserId)
                        .setSql("version = version + 1"));
                ensureTransitionApplied(updated, id);
            }
        } else {
            // Reimbursement void only from draft/rejected; neither holds budget.
            int updated = reimbursementOrderMapper.update(null, new LambdaUpdateWrapper<ReimbursementOrderEntity>()
                    .eq(ReimbursementOrderEntity::getId, id)
                    .in(ReimbursementOrderEntity::getStatus, List.of(STATUS_DRAFT, STATUS_REJECTED))
                    .set(ReimbursementOrderEntity::getStatus, STATUS_VOID)
                    .set(ReimbursementOrderEntity::getUpdatedAt, now)
                    .set(ReimbursementOrderEntity::getUpdatedBy, currentUserId)
                    .setSql("version = version + 1"));
            ensureTransitionApplied(updated, id);
        }

        log.atInfo().addKeyValue("reimbursementId", id).log("Reimbursement order voided");
        return getReimbursement(id);
    }

    // ---------- helpers ----------

    private ReimbursementItemEntity toItemEntity(Long reimbursementId, ReimbursementItemInput input) {
        ReimbursementItemEntity item = new ReimbursementItemEntity();
        item.setReimbursementId(reimbursementId);
        item.setAmount(MoneyUtils.normalize(input.amount()));
        item.setExpenseDate(input.expenseDate());
        item.setVendor(trimOrNull(input.vendor()));
        item.setInvoiceNo(trimOrNull(input.invoiceNo()));
        item.setReceiptFile(trimOrNull(input.receiptFile()));
        item.setDescription(input.description() == null ? null : input.description().trim());
        item.setCounterpartyAccount(trimOrNull(input.counterpartyAccount()));
        return item;
    }

    private String normalizePaymentType(String raw) {
        String type = raw == null ? "" : raw.trim().toLowerCase();
        if (!PAYMENT_REIMBURSEMENT.equals(type) && !PAYMENT_PUBLIC.equals(type)) {
            throw new BusinessException(
                    "REIMBURSEMENT_PAYMENT_TYPE_INVALID",
                    "paymentType must be reimbursement or public_payment. value=" + raw);
        }
        return type;
    }

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

    private void validateReceiptReference(ReimbursementItemInput input) {
        if (StringUtils.hasText(input.receiptFile())) {
            fileStorageService.validateOwnedReceiptReference(input.receiptFile());
        }
    }

    private void ensureReadable(ReimbursementOrderEntity order) {
        CurrentUserTO current = CurrentUserContextHolder.currentOrAnonymous();
        if (current.hasRole("ADMIN") || current.hasRole("APPROVER") || isApplicant(order, current)) {
            return;
        }
        throw new BusinessException(
                "REIMBURSEMENT_FORBIDDEN",
                "无权访问其他申请人的报销单",
                HttpStatus.FORBIDDEN);
    }

    private void ensureOwnerOrAdmin(ReimbursementOrderEntity order) {
        CurrentUserTO current = CurrentUserContextHolder.currentOrAnonymous();
        if (current.hasRole("ADMIN") || isApplicant(order, current)) {
            return;
        }
        throw new BusinessException(
                "REIMBURSEMENT_WRITE_FORBIDDEN",
                "仅申请人本人或管理员可修改该报销单",
                HttpStatus.FORBIDDEN);
    }

    private boolean isApplicant(ReimbursementOrderEntity order, CurrentUserTO current) {
        return order.getApplicant() != null
                && (order.getApplicant().equals(current.username()) || order.getApplicant().equals(current.userId()));
    }

    private void requireAnyRole(CurrentUserTO current, String... roles) {
        for (String role : roles) {
            if (current.hasRole(role)) {
                return;
            }
        }
        throw new BusinessException("REIMBURSEMENT_ROLE_FORBIDDEN", "当前角色无权执行该操作", HttpStatus.FORBIDDEN);
    }

    private List<ReimbursementItemEntity> findItems(Long reimbursementId) {
        return reimbursementItemMapper.selectList(new LambdaQueryWrapper<ReimbursementItemEntity>()
                .eq(ReimbursementItemEntity::getReimbursementId, reimbursementId)
                .orderByAsc(ReimbursementItemEntity::getId));
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

    private Map<Long, List<ReimbursementItemEntity>> itemsByOrder(List<ReimbursementOrderEntity> orders) {
        if (orders.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> orderIds = orders.stream().map(ReimbursementOrderEntity::getId).toList();
        List<ReimbursementItemEntity> items = reimbursementItemMapper.selectList(
                new LambdaQueryWrapper<ReimbursementItemEntity>()
                        .in(ReimbursementItemEntity::getReimbursementId, orderIds)
                        .orderByAsc(ReimbursementItemEntity::getId));
        return items.stream().collect(Collectors.groupingBy(ReimbursementItemEntity::getReimbursementId));
    }

    /** Canonical receipt references of the order's items, for list-view thumbnails. */
    private List<String> receiptFilesOf(List<ReimbursementItemEntity> items) {
        return items.stream()
                .map(ReimbursementItemEntity::getReceiptFile)
                .filter(StringUtils::hasText)
                .toList();
    }

    /** Per-item proof digest for the list view: invoice number, "凭证" when only an image exists, or "缺". */
    private String summarizeProof(List<ReimbursementItemEntity> items) {
        if (items.isEmpty()) {
            return "—";
        }
        List<String> parts = new ArrayList<>();
        for (ReimbursementItemEntity item : items) {
            if (StringUtils.hasText(item.getInvoiceNo())) {
                parts.add(item.getInvoiceNo().trim());
            } else if (StringUtils.hasText(item.getReceiptFile())) {
                parts.add("凭证");
            } else if (StringUtils.hasText(item.getCounterpartyAccount())) {
                parts.add("公卡");
            } else {
                parts.add("缺");
            }
        }
        return String.join("、", parts);
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

    private String trimOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
