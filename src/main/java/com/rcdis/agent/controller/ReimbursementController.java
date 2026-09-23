package com.rcdis.agent.controller;

import java.util.List;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.ReimbursementActionRequest;
import com.rcdis.agent.dto.ReimbursementCreateRequest;
import com.rcdis.agent.dto.ReimbursementPageRequest;
import com.rcdis.agent.dto.ReimbursementUpdateRequest;
import com.rcdis.agent.service.ReimbursementApprovalService;
import com.rcdis.agent.service.ReimbursementService;
import com.rcdis.agent.vo.MaterialCheckVO;
import com.rcdis.agent.vo.ReimbursementDetailVO;
import com.rcdis.agent.vo.ReimbursementVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Validated
@Tag(name = "Reimbursements")
@RestController
@RequestMapping("/api/reimbursements")
@RequiredArgsConstructor
public class ReimbursementController {

    private final ReimbursementService reimbursementService;
    private final ReimbursementApprovalService reimbursementApprovalService;

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
            detail = reimbursementApprovalService.submitAfterCreate(detail.order().id());
        }
        return ApiResponse.success(detail);
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
        // Routed through the orchestration layer so the in-app notification side effect is not skipped.
        return ApiResponse.success(reimbursementApprovalService.voidOrder(id, request));
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
        return ApiResponse.success(reimbursementApprovalService.submit(id, request));
    }

    @Operation(summary = "Withdraw a submitted reimbursement back to draft (applicant or ADMIN)")
    @PostMapping("/{id}/withdraw")
    public ApiResponse<ReimbursementDetailVO> withdrawReimbursement(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ReimbursementActionRequest request) {
        return ApiResponse.success(reimbursementService.withdrawReimbursement(id, request));
    }

    @Operation(summary = "Approve reimbursement order")
    @PreAuthorize("hasAnyRole('ADMIN','APPROVER')")
    @PostMapping("/{id}/approve")
    public ApiResponse<ReimbursementDetailVO> approveReimbursement(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ReimbursementActionRequest request) {
        return ApiResponse.success(reimbursementApprovalService.approve(id, request));
    }

    @Operation(summary = "Reject reimbursement order")
    @PreAuthorize("hasAnyRole('ADMIN','APPROVER')")
    @PostMapping("/{id}/reject")
    public ApiResponse<ReimbursementDetailVO> rejectReimbursement(
            @PathVariable Long id,
            @Valid @RequestBody ReimbursementActionRequest request) {
        return ApiResponse.success(reimbursementApprovalService.reject(id, request));
    }
}
