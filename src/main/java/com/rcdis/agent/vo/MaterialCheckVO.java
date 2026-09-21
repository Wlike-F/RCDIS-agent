package com.rcdis.agent.vo;

import java.util.List;

/**
 * Result of the reimbursement material check.
 * pass=true means no MISSING findings; warnings do not block submission.
 */
public record MaterialCheckVO(
        boolean pass,
        int checkedCount,
        List<Finding> findings
) {

    public record Finding(
            String level,
            Long itemId,
            String label,
            String message
    ) {
    }
}
