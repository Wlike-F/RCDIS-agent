package com.rcdis.agent.mapper;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rcdis.agent.entity.ReimbursementItemEntity;
import com.rcdis.agent.to.ExpenseItemRowTO;
import com.rcdis.agent.to.ExpenseSummaryRowTO;

public interface ReimbursementItemMapper extends BaseMapper<ReimbursementItemEntity> {

    /**
     * Physical delete of all lines of an order. Used when a draft is edited (full line
     * replacement) so removed lines disappear instead of lingering as soft-deleted rows.
     */
    @Delete("delete from reimbursement_item where reimbursement_id = #{reimbursementId}")
    int physicalDeleteByReimbursementId(@Param("reimbursementId") Long reimbursementId);

    /**
     * Expense lines aggregated by one dimension. All arithmetic is done in SQL; the model only
     * reads the numbers back.
     *
     * @param groupBy project / month / applicant / status / paymentType, anything else means one
     *                single {@code ALL} bucket
     */
    List<ExpenseSummaryRowTO> selectExpenseSummary(
            @Param("projectCode") String projectCode,
            @Param("applicant") String applicant,
            @Param("monthFrom") LocalDate monthFrom,
            @Param("monthTo") LocalDate monthTo,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("statuses") List<String> statuses,
            @Param("groupBy") String groupBy,
            @Param("limit") int limit);

    /** Ungrouped totals behind the same filters, so grand totals stay exact when groups truncate. */
    ExpenseSummaryRowTO selectExpenseTotals(
            @Param("projectCode") String projectCode,
            @Param("applicant") String applicant,
            @Param("monthFrom") LocalDate monthFrom,
            @Param("monthTo") LocalDate monthTo,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("statuses") List<String> statuses);

    /** Expense lines with their owning order and project, newest expense first. */
    List<ExpenseItemRowTO> selectExpenseItems(
            @Param("projectCode") String projectCode,
            @Param("applicant") String applicant,
            @Param("monthFrom") LocalDate monthFrom,
            @Param("monthTo") LocalDate monthTo,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("statuses") List<String> statuses,
            @Param("limit") int limit);
}
