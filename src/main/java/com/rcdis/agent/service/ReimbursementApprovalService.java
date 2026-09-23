package com.rcdis.agent.service;

import com.rcdis.agent.dto.ReimbursementActionRequest;
import com.rcdis.agent.vo.ReimbursementDetailVO;

/**
 * Orchestrates reimbursement state transitions together with their Feishu notifications.
 *
 * <p>Both the REST API and the Feishu card callback go through this service so that a decision made
 * by clicking a card button produces exactly the same state change, notifications and audit trail
 * as one made in the web UI.</p>
 */
public interface ReimbursementApprovalService {

    /**
     * Applied when an approver rejects from a Feishu card and leaves the optional reason empty, so
     * that the reject reason column and the audit trail are never blank.
     */
    String DEFAULT_REJECT_REASON = "请检查材料后再次提交";

    /**
     * Submits an order, then pushes the group notification card and the private interactive
     * approval cards to every bound approver.
     */
    ReimbursementDetailVO submit(Long id, ReimbursementActionRequest request);

    /**
     * Submits a freshly created order. When the material check fails the order stays DRAFT and the
     * draft detail is returned so the caller can complete the missing materials.
     */
    ReimbursementDetailVO submitAfterCreate(Long id);

    ReimbursementDetailVO approve(Long id, ReimbursementActionRequest request);

    ReimbursementDetailVO reject(Long id, ReimbursementActionRequest request);

    /**
     * Voids a DRAFT/REJECTED order and pushes the in-app voided notification to the applicant.
     * Web UI and Agent confirmation must both enter through this method so the side effect is never
     * skipped.
     */
    ReimbursementDetailVO voidOrder(Long id, ReimbursementActionRequest request);

    /**
     * Approve on behalf of a latency-bounded caller such as the Feishu card callback, which must
     * answer within 3 seconds. The state change commits synchronously while the outbound
     * notification, which needs an external HTTP round trip with retries, is deferred to an executor.
     */
    ReimbursementDetailVO approveDeferredNotify(Long id, ReimbursementActionRequest request);

    /**
     * Reject counterpart of {@link #approveDeferredNotify(Long, ReimbursementActionRequest)}.
     */
    ReimbursementDetailVO rejectDeferredNotify(Long id, ReimbursementActionRequest request);
}
