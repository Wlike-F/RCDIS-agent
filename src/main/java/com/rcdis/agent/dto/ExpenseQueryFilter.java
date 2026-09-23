package com.rcdis.agent.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Filters for expense queries over reimbursement lines.
 *
 * @param projectCode project code, exact match, nullable
 * @param applicant   restrict to one applicant (record scope for non-privileged roles), nullable
 * @param month       expense month as {@code YYYY-MM}, nullable
 * @param dateFrom    inclusive expense date lower bound, nullable
 * @param dateTo      inclusive expense date upper bound, nullable
 * @param statuses    order statuses to include, empty means the service default
 */
public record ExpenseQueryFilter(
        String projectCode,
        String applicant,
        String month,
        LocalDate dateFrom,
        LocalDate dateTo,
        List<String> statuses
) {

    public static ExpenseQueryFilter unfiltered() {
        return new ExpenseQueryFilter(null, null, null, null, null, List.of());
    }
}
