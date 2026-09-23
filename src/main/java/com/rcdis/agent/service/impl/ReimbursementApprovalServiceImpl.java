package com.rcdis.agent.service.impl;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.dto.ReimbursementActionRequest;
import com.rcdis.agent.service.FeishuNotificationService;
import com.rcdis.agent.service.InAppNotificationService;
import com.rcdis.agent.service.ReimbursementApprovalService;
import com.rcdis.agent.service.ReimbursementService;
import com.rcdis.agent.vo.ReimbursementDetailVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Deliberately not transactional: each method first lets the transactional
 * {@link ReimbursementService} commit the state change, and only then attempts the Feishu
 * notifications. Running an outbound HTTP call inside the database transaction would hold a
 * connection for the whole round trip and could roll back a committed business decision.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReimbursementApprovalServiceImpl implements ReimbursementApprovalService {

    private static final String MATERIALS_INCOMPLETE_CODE = "REIMBURSEMENT_MATERIALS_INCOMPLETE";

    private final ReimbursementService reimbursementService;
    private final FeishuNotificationService feishuNotificationService;
    private final InAppNotificationService inAppNotificationService;

    @Qualifier("notificationTaskExecutor")
    private final Executor notificationTaskExecutor;

    @Override
    public ReimbursementDetailVO submit(Long id, ReimbursementActionRequest request) {
        ReimbursementDetailVO detail = reimbursementService.submitReimbursement(id, request);
        // A public payment books directly with no approval step, so no approval cards are pushed (v1).
        if (isReimbursement(detail)) {
            notifyQuietly(id, "submit", detail, feishuNotificationService::notifyReimbursementSubmitted);
            notifyQuietly(id, "submit-approval-cards", detail,
                    feishuNotificationService::notifyReimbursementApprovalRequested);
            notifyQuietly(id, "submit-in-app", detail, inAppNotificationService::notifySubmitted);
        }
        return detail;
    }

    private boolean isReimbursement(ReimbursementDetailVO detail) {
        String paymentType = detail.order() == null ? null : detail.order().paymentType();
        return !"public_payment".equals(paymentType);
    }

    @Override
    public ReimbursementDetailVO submitAfterCreate(Long id) {
        try {
            return submit(id, new ReimbursementActionRequest("创建并提交"));
        } catch (BusinessException exception) {
            if (!MATERIALS_INCOMPLETE_CODE.equals(exception.getCode())) {
                throw exception;
            }
            log.atInfo()
                    .addKeyValue("reimbursementId", id)
                    .log("Submit-now order kept as draft because material check failed");
            return reimbursementService.getReimbursement(id);
        }
    }

    @Override
    public ReimbursementDetailVO approve(Long id, ReimbursementActionRequest request) {
        ReimbursementDetailVO detail = reimbursementService.approveReimbursement(id, request);
        notifyQuietly(id, "approve", detail, feishuNotificationService::notifyReimbursementApproved);
        notifyQuietly(id, "approve-in-app", detail, inAppNotificationService::notifyApproved);
        return detail;
    }

    @Override
    public ReimbursementDetailVO reject(Long id, ReimbursementActionRequest request) {
        ReimbursementDetailVO detail = reimbursementService.rejectReimbursement(id, request);
        notifyQuietly(id, "reject", detail, feishuNotificationService::notifyReimbursementRejected);
        notifyQuietly(id, "reject-in-app", detail, inAppNotificationService::notifyRejected);
        return detail;
    }

    @Override
    public ReimbursementDetailVO voidOrder(Long id, ReimbursementActionRequest request) {
        ReimbursementDetailVO detail = reimbursementService.voidReimbursement(id, request);
        notifyQuietly(id, "void-in-app", detail, inAppNotificationService::notifyVoided);
        return detail;
    }

    @Override
    public ReimbursementDetailVO approveDeferredNotify(Long id, ReimbursementActionRequest request) {
        ReimbursementDetailVO detail = reimbursementService.approveReimbursement(id, request);
        notifyDeferred(id, "approve", detail, feishuNotificationService::notifyReimbursementApproved);
        notifyQuietly(id, "approve-in-app", detail, inAppNotificationService::notifyApproved);
        return detail;
    }

    @Override
    public ReimbursementDetailVO rejectDeferredNotify(Long id, ReimbursementActionRequest request) {
        ReimbursementDetailVO detail = reimbursementService.rejectReimbursement(id, request);
        notifyDeferred(id, "reject", detail, feishuNotificationService::notifyReimbursementRejected);
        notifyQuietly(id, "reject-in-app", detail, inAppNotificationService::notifyRejected);
        return detail;
    }

    /**
     * Hands the notification to an executor so the caller stays inside its latency budget. The
     * executor's task decorator carries the current user across, so the outbox entry and any audit
     * record still name the real approver. A saturated executor falls back to an inline attempt
     * rather than silently dropping the notification for an already committed decision.
     */
    private void notifyDeferred(
            Long id,
            String action,
            ReimbursementDetailVO detail,
            Consumer<ReimbursementDetailVO> notification) {
        try {
            notificationTaskExecutor.execute(() -> notifyQuietly(id, action, detail, notification));
        } catch (RuntimeException exception) {
            log.atWarn()
                    .setCause(exception)
                    .addKeyValue("reimbursementId", id)
                    .addKeyValue("action", action)
                    .log("Notification executor rejected the task; sending inline instead");
            notifyQuietly(id, action, detail, notification);
        }
    }

    /**
     * Best-effort notification after the business transaction committed; failures are recorded in
     * the notification outbox and never block or roll back the transition.
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
}
