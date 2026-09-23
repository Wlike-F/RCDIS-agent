package com.rcdis.agent.service;

import java.util.List;

import com.rcdis.agent.dto.ExpenseQueryFilter;
import com.rcdis.agent.to.ExpenseItemRowTO;
import com.rcdis.agent.vo.ExpenseSummaryVO;

/**
 * Deterministic expense analytics over reimbursement lines, backing the Agent's read-only expense
 * tools. Every number is produced by SQL aggregation; the model never computes amounts itself.
 */
public interface ExpenseAnalysisService {

    /** Default order statuses counted as spend when the caller does not narrow them down. */
    List<String> defaultStatuses();

    /** Expenses aggregated by one dimension, with exact grand totals behind the filter. */
    ExpenseSummaryVO summarize(ExpenseQueryFilter filter, String groupBy, int limit);

    /** Expense lines behind the filter, newest expense first. */
    List<ExpenseItemRowTO> listItems(ExpenseQueryFilter filter, int limit);
}
