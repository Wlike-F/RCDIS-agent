package com.rcdis.agent.service;

import com.rcdis.agent.to.CardActionOutcomeTO;
import com.rcdis.agent.to.CardActionRequestTO;

/**
 * Handles a Feishu approval card action.
 *
 * <p>Transport agnostic on purpose: the WebSocket long-connection listener and the HTTP callback
 * endpoint both normalize their payload into {@link CardActionRequestTO} and call this service, so
 * authorization, idempotency and execution exist exactly once.</p>
 */
public interface FeishuCardActionService {

    /**
     * Validates, authorizes and executes one card action.
     *
     * <p>Never throws for a business rejection: those are returned as an error or warning toast so
     * that the approver sees the reason inside Feishu. Must complete well within the 3 second budget
     * Feishu allows, which is why outbound notifications are deferred by the approval service.</p>
     */
    CardActionOutcomeTO handleCardAction(CardActionRequestTO request);
}
