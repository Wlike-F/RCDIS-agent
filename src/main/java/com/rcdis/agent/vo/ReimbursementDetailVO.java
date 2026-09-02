package com.rcdis.agent.vo;

import java.util.List;

/**
 * Reimbursement order detail: order summary plus its line items.
 */
public record ReimbursementDetailVO(
        ReimbursementVO order,
        List<ReimbursementItemVO> items
) {
}
