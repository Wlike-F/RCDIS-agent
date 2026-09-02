package com.rcdis.agent.service;

import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.FeishuMessageResponse;
import com.rcdis.agent.dto.FeishuTestMessageRequest;
import com.rcdis.agent.dto.NotificationOutboxPageRequest;
import com.rcdis.agent.vo.FeishuConfigStatusVO;
import com.rcdis.agent.vo.NotificationOutboxVO;
import com.rcdis.agent.vo.ReimbursementDetailVO;

public interface FeishuNotificationService {

    FeishuConfigStatusVO getConfigStatus();

    PageResponse<NotificationOutboxVO> pageNotifications(NotificationOutboxPageRequest request);

    FeishuMessageResponse sendTestMessage(FeishuTestMessageRequest request, CurrentUserTO currentUser);

    /**
     * Sends the reimbursement-submitted card notification through the outbox pipeline.
     */
    FeishuMessageResponse notifyReimbursementSubmitted(ReimbursementDetailVO detail);

    /**
     * Sends the reimbursement-approved card notification through the outbox pipeline.
     */
    FeishuMessageResponse notifyReimbursementApproved(ReimbursementDetailVO detail);

    /**
     * Sends the reimbursement-rejected card notification through the outbox pipeline.
     */
    FeishuMessageResponse notifyReimbursementRejected(ReimbursementDetailVO detail);
}
