package com.rcdis.agent.mapper;

import java.util.Collection;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rcdis.agent.entity.ReimbursementItemEntity;

public interface ReimbursementItemMapper extends BaseMapper<ReimbursementItemEntity> {

    /**
     * Physical delete: reimbursement_item rows are pure link records and
     * expense_id carries a DB-level unique constraint, so soft delete cannot
     * release the linked expenses for reuse by other orders.
     */
    @Delete("delete from reimbursement_item where reimbursement_id = #{reimbursementId}")
    int physicalDeleteByReimbursementId(@Param("reimbursementId") Long reimbursementId);

    @Delete("<script>delete from reimbursement_item where reimbursement_id = #{reimbursementId}"
            + " and expense_id in <foreach collection='expenseIds' item='expenseId' open='(' separator=',' close=')'>#{expenseId}</foreach></script>")
    int physicalDeleteByReimbursementIdAndExpenseIds(
            @Param("reimbursementId") Long reimbursementId,
            @Param("expenseIds") Collection<Long> expenseIds);
}
