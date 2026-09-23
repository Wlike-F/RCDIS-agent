package com.rcdis.agent.vo;

import java.math.BigDecimal;
import java.util.List;

import com.rcdis.agent.to.ExpenseSummaryRowTO;

/**
 * Aggregated expenses grouped by one dimension, with exact totals computed by a separate ungrouped
 * query so truncating the group list never distorts the totals.
 *
 * @param groupBy     the dimension actually used (project/month/applicant/status/paymentType/none)
 * @param statusScope order statuses included in the aggregation
 * @param rows        grouped results, largest amount first
 * @param groupCount  number of rows returned
 * @param totalCount  total number of expense lines behind the filter (not limited by groups)
 * @param grandTotal  total amount behind the filter (not limited by groups)
 */
public record ExpenseSummaryVO(
        String groupBy,
        List<String> statusScope,
        List<ExpenseSummaryRowTO> rows,
        int groupCount,
        long totalCount,
        BigDecimal grandTotal
) {
}
