package com.rcdis.agent.to;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;

/**
 * One aggregated expense row produced by deterministic SQL (no LLM arithmetic).
 *
 * <p>Mutable bean rather than a record: MyBatis maps snake_case result columns onto setters, and
 * record constructor auto-mapping is not reliable across drivers.</p>
 */
@Getter
@Setter
public class ExpenseSummaryRowTO {

    /** Grouping label (project, month, applicant, status or payment type). */
    private String groupKey;
    /** Number of expense lines in this group. */
    private Long itemCount;
    /** Summed amount of those lines. */
    private BigDecimal totalAmount;
}
