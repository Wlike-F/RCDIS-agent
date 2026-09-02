package com.rcdis.agent.mapper;

import java.time.LocalDate;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rcdis.agent.entity.ExpenseRecordEntity;

public interface ExpenseRecordMapper extends BaseMapper<ExpenseRecordEntity> {

    /**
     * Page expenses including soft-deleted (VOIDED) records.
     * See mapper XML: bypasses the global logic-delete filter on purpose.
     */
    Page<ExpenseRecordEntity> selectExpensePage(
            Page<ExpenseRecordEntity> page,
            @Param("projectId") Long projectId,
            @Param("budgetCategoryId") Long budgetCategoryId,
            @Param("status") String status,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("keyword") String keyword);
}
