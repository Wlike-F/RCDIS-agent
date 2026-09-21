package com.rcdis.agent.service;

import com.rcdis.agent.dto.ChatConfirmResponse;
import com.rcdis.agent.entity.AgentPendingActionEntity;
import com.rcdis.agent.to.AgentProposalTO;

/**
 * Proposal / commit separation for high-risk Agent write operations.
 *
 * <p>Write tools call {@link #createProposal(AgentProposalTO)} to persist a {@code PENDING} row and
 * then tell the model "nothing was executed yet". The actual mutation only happens later, in
 * {@link #resolveConfirmation(String, String, boolean)}, when a human approves. This guarantees the
 * LLM can propose but never directly write financial data.</p>
 */
public interface AgentPendingActionService {

    /**
     * Persists a proposal and returns it (caller emits the requires_confirmation SSE event).
     *
     * @param actorUserId the verified user proposing the action, passed explicitly because tool
     *                    execution may run on a Reactor thread where the request-scoped
     *                    {@code CurrentUserContextHolder} ThreadLocal is empty
     */
    AgentPendingActionEntity createProposal(AgentProposalTO proposal, String actorUserId);

    /**
     * Applies the user's decision. Approving replays the stored arguments through the real domain
     * service and marks the row {@code EXECUTED}; rejecting marks it {@code REJECTED}. Idempotent
     * guards: ownership, {@code PENDING} status and expiry are all re-checked here.
     */
    ChatConfirmResponse resolveConfirmation(String conversationId, String confirmationId, boolean approved);

    int recoverExpiredExecutions();
}
