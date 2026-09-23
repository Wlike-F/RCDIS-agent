package com.rcdis.agent.service;

import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.vo.AppNotificationVO;
import com.rcdis.agent.vo.ReimbursementDetailVO;

/**
 * In-app notification store for reimbursement lifecycle events.
 *
 * <p>Notification creation is called from the orchestration layer
 * ({@code ReimbursementApprovalService}) right after the business transaction committed, so a failure
 * here must never be raised back into the caller. Recipients are matched by the
 * {@code sys_user.username} login name, which equals the JWT {@code userId}.</p>
 */
public interface InAppNotificationService {

    /**
     * Pushes a submission receipt to the submitting user and a todo to every active bound approver.
     */
    void notifySubmitted(ReimbursementDetailVO detail);

    void notifyApproved(ReimbursementDetailVO detail);

    void notifyRejected(ReimbursementDetailVO detail);

    void notifyVoided(ReimbursementDetailVO detail);

    /**
     * Lists the current user's notifications, newest first.
     */
    PageResponse<AppNotificationVO> pageMyNotifications(long current, long size);

    long countUnread();

    void markRead(Long id);

    void markAllRead();
}
