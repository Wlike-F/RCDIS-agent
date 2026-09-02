package com.rcdis.agent.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.rcdis.agent.entity.BudgetCategoryEntity;
import com.rcdis.agent.entity.ExpenseRecordEntity;
import com.rcdis.agent.entity.ResearchProjectEntity;

public record ExpenseVO(
        Long id,
        Long projectId,
        String projectCode,
        String projectName,
        Long budgetCategoryId,
        String categoryCode,
        String categoryName,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal amount,
        LocalDate expenseDate,
        String vendor,
        String invoiceNo,
        String receiptFile,
        String description,
        String status,
        Integer version
) {

    public static ExpenseVO fromEntity(
            ExpenseRecordEntity expense,
            ResearchProjectEntity project,
            BudgetCategoryEntity budgetCategory) {
        return new ExpenseVO(
                expense.getId(),
                expense.getProjectId(),
                project == null ? null : project.getProjectCode(),
                project == null ? null : project.getProjectName(),
                expense.getBudgetCategoryId(),
                budgetCategory == null ? null : budgetCategory.getCategoryCode(),
                budgetCategory == null ? null : budgetCategory.getCategoryName(),
                expense.getAmount(),
                expense.getExpenseDate(),
                expense.getVendor(),
                expense.getInvoiceNo(),
                expense.getReceiptFile(),
                expense.getDescription(),
                expense.getStatus(),
                expense.getVersion());
    }
}
