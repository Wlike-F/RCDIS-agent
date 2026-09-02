package com.rcdis.agent.controller;

import java.util.List;
import java.util.function.Consumer;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.ReimbursementActionRequest;
import com.rcdis.agent.dto.ReimbursementCreateRequest;
import com.rcdis.agent.dto.ReimbursementPageRequest;
import com.rcdis.agent.dto.ReimbursementUpdateRequest;
import com.rcdis.agent.service.FeishuNotificationService;
import com.rcdis.agent.service.ReimbursementService;
import com.rcdis.agent.vo.ExpenseVO;
import com.rcdis.agent.vo.MaterialCheckVO;
import com.rcdis.agent.vo.ReimbursementDetailVO;
import com.rcdis.agent.vo.ReimbursementVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Validated
@Tag(name = "Reimbursements")
@RestController
@RequestMapping("/api/reimbursements")
@RequiredArgsConstructor
public class ReimbursementController {

    private final ReimbursementService reimbursementService;
    private final FeishuNotificationService feishuNotificationService;

    @Operation(summary = "Page reimbursement orders")
    @GetMapping
    public ApiResponse<PageResponse<ReimbursementVO>> pageReimbursements(
            @Valid @ParameterObject ReimbursementPageRequest request) {
        return ApiResponse.success(reimbursementService.pageReimbursements(request));
    }

    @Operation(summary = "Get reimbursement order detail")
    @GetMapping("/{id}")
    public ApiResponse<ReimbursementDetailVO> getReimbursement(@PathVariable Long id) {
        return ApiResponse.success(reimbursementService.getReimbursement(id));
    }

    @Operation(summary = "Create reimbursement order (draft), optionally submit immediately")
    @PostMapping
    public ApiResponse<ReimbursementDetailVO> createReimbursement(
            @Valid @RequestBody ReimbursementCreateRequest request) {
        ReimbursementDetailVO detail = reimbursementService.createReimbursement(request);
        if (Boolean.TRUE.equals(request.submitNow())) {
            detail = submitAfterCreate(detail.order().id());
        }
        return ApiResponse.success(detail);
    }

    /**
     * Submit the freshly created order; when the material check fails the order stays DRAFT
     * and the draft detail is returned so the caller can complete the missing materials.
     */
    private ReimbursementDetailVO submitAfterCreate(Long id) {
        try {
            ReimbursementDetailVO submitted = reimbursementService.submitReimbursement(
                    id, new ReimbursementActionRequest("创建并提交"));
            notifyQuietly(id, "submit", submitted, feishuNotificationService::notifyReimbursementSubmitted);
            return submitted;
        } catch (BusinessException exception) {
            if (!"REIMBURSEMENT_MATERIALS_INCOMPLETE".equals(exception.getCode())) {
                throw exception;
            }
            log.atInfo()
                    .addKeyValue("reimbursementId", id)
                    .log("Submit-now order kept as draft because material check failed");
            return reimbursementService.getReimbursement(id);
        }
    }

    @Operation(summary = "Update reimbursement draft (applicant / linked expenses)")
    @PutMapping("/{id}")
    public ApiResponse<ReimbursementDetailVO> updateReimbursement(
            @PathVariable Long id,
            @Valid @RequestBody ReimbursementUpdateRequest request) {
        return ApiResponse.success(reimbursementService.updateReimbursement(id, request));
    }

    @Operation(summary = "Void a DRAFT/REJECTED reimbursement order and release linked expenses")
    @PostMapping("/{id}/void")
    public ApiResponse<ReimbursementDetailVO> voidReimbursement(
            @PathVariable Long id,
            @Valid @RequestBody ReimbursementActionRequest request) {
        return ApiResponse.success(reimbursementService.voidReimbursement(id, request));
    }

    @Operation(summary = "List expenses available for reimbursement")
    @GetMapping("/available-expenses")
    public ApiResponse<List<ExpenseVO>> listAvailableExpenses(
            @RequestParam Long projectId,
            @RequestParam(required = false) Long excludeOrderId) {
        return ApiResponse.success(reimbursementService.listAvailableExpenses(projectId, excludeOrderId));
    }

    @Operation(summary = "Check reimbursement materials")
    @GetMapping("/{id}/material-check")
    public ApiResponse<MaterialCheckVO> checkMaterials(@PathVariable Long id) {
        return ApiResponse.success(reimbursementService.checkMaterials(id));
    }

    @Operation(summary = "Submit reimbursement order for approval")
    @PostMapping("/{id}/submit")
    public ApiResponse<ReimbursementDetailVO> submitReimbursement(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ReimbursementActionRequest request) {
        ReimbursementDetailVO detail = reimbursementService.submitReimbursement(id, request);
        notifyQuietly(id, "submit", detail, feishuNotificationService::notifyReimbursementSubmitted);
        return ApiResponse.success(detail);
    }

    /**
     * Best-effort Feishu card notification after the business transaction committed;
     * failures are recorded in the notification outbox and never block the transition.
     */
    private void notifyQuietly(
            Long id,
            String action,
            ReimbursementDetailVO detail,
            Consumer<ReimbursementDetailVO> notification) {
        try {
            notification.accept(detail);
        } catch (RuntimeException exception) {
            log.atWarn()
                    .setCause(exception)
                    .addKeyValue("reimbursementId", id)
                    .addKeyValue("action", action)
                    .log("Failed to send reimbursement Feishu notification");
        }
    }

    @Operation(summary = "Approve reimbursement order")
    @PostMapping("/{id}/approve")
    public ApiResponse<ReimbursementDetailVO> approveReimbursement(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ReimbursementActionRequest request) {
        ReimbursementDetailVO detail = reimbursementService.approveReimbursement(id, request);
        notifyQuietly(id, "approve", detail, feishuNotificationService::notifyReimbursementApproved);
        return ApiResponse.success(detail);
    }

    @Operation(summary = "Reject reimbursement order")
    @PostMapping("/{id}/reject")
    public ApiResponse<ReimbursementDetailVO> rejectReimbursement(
            @PathVariable Long id,
            @Valid @RequestBody ReimbursementActionRequest request) {
        ReimbursementDetailVO detail = reimbursementService.rejectReimbursement(id, request);
        notifyQuietly(id, "reject", detail, feishuNotificationService::notifyReimbursementRejected);
        return ApiResponse.success(detail);
    }
}
