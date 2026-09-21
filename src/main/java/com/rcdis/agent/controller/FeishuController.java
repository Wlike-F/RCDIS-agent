package com.rcdis.agent.controller;

import java.util.List;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rcdis.agent.common.context.CurrentUser;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.FeishuApproverBindRequest;
import com.rcdis.agent.dto.FeishuApproverDeleteRequest;
import com.rcdis.agent.dto.FeishuMessageResponse;
import com.rcdis.agent.dto.FeishuTestMessageRequest;
import com.rcdis.agent.dto.NotificationOutboxPageRequest;
import com.rcdis.agent.dto.NotificationTemplateCreateRequest;
import com.rcdis.agent.dto.NotificationTemplateDeleteRequest;
import com.rcdis.agent.dto.NotificationTemplateUpdateRequest;
import com.rcdis.agent.service.FeishuApproverService;
import com.rcdis.agent.service.FeishuNotificationService;
import com.rcdis.agent.service.NotificationTemplateService;
import com.rcdis.agent.vo.FeishuApproverVO;
import com.rcdis.agent.vo.FeishuChatMemberVO;
import com.rcdis.agent.vo.FeishuConfigStatusVO;
import com.rcdis.agent.vo.FeishuNotificationTemplateVO;
import com.rcdis.agent.vo.NotificationOutboxVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Validated
@Tag(name = "Feishu")
@RestController
@RequestMapping("/api/feishu")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class FeishuController {

    private final FeishuNotificationService feishuNotificationService;
    private final NotificationTemplateService notificationTemplateService;
    private final FeishuApproverService feishuApproverService;

    @Operation(summary = "Get Feishu notification configuration status")
    @GetMapping("/config")
    public ApiResponse<FeishuConfigStatusVO> getConfigStatus() {
        return ApiResponse.success(feishuNotificationService.getConfigStatus());
    }

    @Operation(summary = "List Feishu notification templates")
    @GetMapping("/templates")
    public ApiResponse<List<FeishuNotificationTemplateVO>> listTemplates() {
        return ApiResponse.success(notificationTemplateService.listTemplates());
    }

    @Operation(summary = "Create Feishu notification template")
    @PostMapping("/templates")
    public ApiResponse<FeishuNotificationTemplateVO> createTemplate(
            @Valid @RequestBody NotificationTemplateCreateRequest request) {
        return ApiResponse.success(notificationTemplateService.createTemplate(request));
    }

    @Operation(summary = "Update Feishu notification template")
    @PutMapping("/templates/{id}")
    public ApiResponse<FeishuNotificationTemplateVO> updateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody NotificationTemplateUpdateRequest request) {
        return ApiResponse.success(notificationTemplateService.updateTemplate(id, request));
    }

    @Operation(summary = "Toggle Feishu notification template status")
    @PostMapping("/templates/{id}/toggle-status")
    public ApiResponse<FeishuNotificationTemplateVO> toggleTemplateStatus(@PathVariable Long id) {
        return ApiResponse.success(notificationTemplateService.toggleTemplateStatus(id));
    }

    @Operation(summary = "Delete Feishu notification template")
    @DeleteMapping("/templates/{id}")
    public ApiResponse<Void> deleteTemplate(
            @PathVariable Long id,
            @Valid @RequestBody NotificationTemplateDeleteRequest request) {
        notificationTemplateService.deleteTemplate(id, request);
        return ApiResponse.success(null);
    }

    @Operation(summary = "Page Feishu notification outbox records")
    @GetMapping("/notifications")
    public ApiResponse<PageResponse<NotificationOutboxVO>> pageNotifications(
            @Valid @ParameterObject NotificationOutboxPageRequest request
    ) {
        return ApiResponse.success(feishuNotificationService.pageNotifications(request));
    }

    @Operation(summary = "Send test Feishu text message")
    @PostMapping("/test-message")
    public ApiResponse<FeishuMessageResponse> sendTestMessage(
            @CurrentUser CurrentUserTO currentUser,
            @Valid @RequestBody FeishuTestMessageRequest request
    ) {
        return ApiResponse.success(feishuNotificationService.sendTestMessage(request, currentUser));
    }

    @Operation(summary = "List bound Feishu approvers")
    @GetMapping("/approvers")
    public ApiResponse<List<FeishuApproverVO>> listApprovers() {
        return ApiResponse.success(feishuApproverService.listApprovers());
    }

    @Operation(summary = "List approval group members so an approver can be picked instead of typed")
    @GetMapping("/chat-members")
    public ApiResponse<List<FeishuChatMemberVO>> listChatMembers() {
        return ApiResponse.success(feishuApproverService.listChatMembers());
    }

    @Operation(summary = "Bind a Feishu user as a reimbursement approver")
    @PostMapping("/approvers")
    public ApiResponse<FeishuApproverVO> bindApprover(@Valid @RequestBody FeishuApproverBindRequest request) {
        return ApiResponse.success(feishuApproverService.bindApprover(request));
    }

    @Operation(summary = "Enable or disable an approver binding")
    @PostMapping("/approvers/{id}/status")
    public ApiResponse<FeishuApproverVO> toggleApproverStatus(@PathVariable Long id) {
        return ApiResponse.success(feishuApproverService.toggleApproverStatus(id));
    }

    @Operation(summary = "Unbind an approver")
    @DeleteMapping("/approvers/{id}")
    public ApiResponse<Void> unbindApprover(
            @PathVariable Long id,
            @Valid @RequestBody FeishuApproverDeleteRequest request) {
        feishuApproverService.unbindApprover(id, request);
        return ApiResponse.success(null);
    }
}
