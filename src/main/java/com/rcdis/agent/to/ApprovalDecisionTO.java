package com.rcdis.agent.to;

import java.time.OffsetDateTime;

/**
 * Outcome of one approval decision, used to render the card that replaces an approval card.
 *
 * @param approved    true for approve, false for reject
 * @param decidedBy   display name of the person who decided, shown on the card and in the audit log
 * @param decidedAt   when the decision was recorded
 * @param rejectReason reject reason; null or blank for approvals, and for rejects where the
 *                     approver left the optional card input empty
 */
public record ApprovalDecisionTO(
        boolean approved,
        String decidedBy,
        OffsetDateTime decidedAt,
        String rejectReason
) {
}
