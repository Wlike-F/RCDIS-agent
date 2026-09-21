package com.rcdis.agent.service;

import java.util.List;
import java.util.Map;

import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.FeishuMessageResponse;
import com.rcdis.agent.dto.FeishuTestMessageRequest;
import com.rcdis.agent.dto.NotificationOutboxPageRequest;
import com.rcdis.agent.to.ApprovalDecisionTO;
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

    /**
     * Private-messages the interactive approval card (card JSON 2.0 with approve and reject buttons)
     * to every active bound approver, skipping the applicant themself.
     *
     * @return one outbox response per approver contacted; empty when the feature is disabled or no
     *         approver is bound
     */
    List<FeishuMessageResponse> notifyReimbursementApprovalRequested(ReimbursementDetailVO detail);

    /**
     * Renders the card that replaces an approval card in place after a decision. Sends nothing; the
     * card callback handler returns it inside its response so the approver sees the outcome
     * immediately without consuming the limited card update token.
     */
    Map<String, Object> renderApprovalDecisionCard(ReimbursementDetailVO detail, ApprovalDecisionTO decision);
}
