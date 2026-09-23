package com.rcdis.agent.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.rcdis.agent.dto.ExpenseQueryFilter;
import com.rcdis.agent.mapper.ReimbursementItemMapper;
import com.rcdis.agent.service.ExpenseAnalysisService;
import com.rcdis.agent.to.ExpenseItemRowTO;
import com.rcdis.agent.to.ExpenseSummaryRowTO;
import com.rcdis.agent.vo.ExpenseSummaryVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin, read-only aggregation layer. Group dimensions are whitelisted so the value can be echoed
 * into the SQL fragment without opening an injection surface, and statuses are always normalised to
 * a concrete list so a missing filter never silently widens the scope of money numbers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpenseAnalysisServiceImpl implements ExpenseAnalysisService {

    private static final Set<String> GROUP_DIMENSIONS =
            Set.of("project", "month", "applicant", "status", "paymentType");
    private static final Set<String> KNOWN_STATUSES =
            Set.of("draft", "submitted", "approved", "rejected", "void");
    private static final List<String> DEFAULT_STATUSES = List.of("approved");
    private static final int MAX_LIMIT = 200;

    private final ReimbursementItemMapper reimbursementItemMapper;

    @Override
    public List<String> defaultStatuses() {
        return DEFAULT_STATUSES;
    }

    @Override
    public ExpenseSummaryVO summarize(ExpenseQueryFilter filter, String groupBy, int limit) {
        String dimension = normalizeGroupBy(groupBy);
        List<String> statuses = normalizeStatuses(filter.statuses());
        int capped = capLimit(limit);

        List<ExpenseSummaryRowTO> rows = reimbursementItemMapper.selectExpenseSummary(
                trim(filter.projectCode()), trim(filter.applicant()),
                monthStart(filter.month()), monthEndExclusive(filter.month()),
                filter.dateFrom(), filter.dateTo(), statuses, dimension, capped);
        ExpenseSummaryRowTO totals = reimbursementItemMapper.selectExpenseTotals(
                trim(filter.projectCode()), trim(filter.applicant()),
                monthStart(filter.month()), monthEndExclusive(filter.month()),
                filter.dateFrom(), filter.dateTo(), statuses);

        long totalCount = totals == null || totals.getItemCount() == null ? 0L : totals.getItemCount();
        BigDecimal grandTotal = totals == null || totals.getTotalAmount() == null
                ? BigDecimal.ZERO
                : totals.getTotalAmount();

        log.atDebug()
                .addKeyValue("groupBy", dimension)
                .addKeyValue("statuses", statuses)
                .addKeyValue("groupCount", rows.size())
                .log("Expense summary aggregated");
        return new ExpenseSummaryVO(dimension, statuses, rows, rows.size(), totalCount, grandTotal);
    }

    @Override
    public List<ExpenseItemRowTO> listItems(ExpenseQueryFilter filter, int limit) {
        return reimbursementItemMapper.selectExpenseItems(
                trim(filter.projectCode()), trim(filter.applicant()),
                monthStart(filter.month()), monthEndExclusive(filter.month()),
                filter.dateFrom(), filter.dateTo(), normalizeStatuses(filter.statuses()),
                capLimit(limit));
    }

    /**
     * A month filter becomes a half-open date range so the SQL stays portable (no driver-specific
     * date formatting) and can still use an index on the expense date. Blank means no bound.
     */
    static LocalDate monthStart(String month) {
        return StringUtils.hasText(month) ? parseMonth(month).atDay(1) : null;
    }

    static LocalDate monthEndExclusive(String month) {
        return StringUtils.hasText(month) ? parseMonth(month).plusMonths(1).atDay(1) : null;
    }

    private static java.time.YearMonth parseMonth(String month) {
        if (!StringUtils.hasText(month)) {
            throw new IllegalArgumentException("month 不能为空");
        }
        try {
            return java.time.YearMonth.parse(month.trim());
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("month 必须是 YYYY-MM 格式，收到：" + month);
        }
    }

    private static String normalizeGroupBy(String groupBy) {
        String candidate = trim(groupBy);
        if (candidate == null) {
            return "none";
        }
        for (String dimension : GROUP_DIMENSIONS) {
            if (dimension.equalsIgnoreCase(candidate)) {
                return dimension;
            }
        }
        return "none";
    }

    /** Unknown statuses are dropped; an empty result falls back to the default spend scope. */
    private static List<String> normalizeStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return DEFAULT_STATUSES;
        }
        List<String> normalized = statuses.stream()
                .map(ExpenseAnalysisServiceImpl::trim)
                .filter(java.util.Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(KNOWN_STATUSES::contains)
                .distinct()
                .toList();
        return normalized.isEmpty() ? DEFAULT_STATUSES : normalized;
    }

    private static int capLimit(int limit) {
        if (limit <= 0) {
            return 50;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
