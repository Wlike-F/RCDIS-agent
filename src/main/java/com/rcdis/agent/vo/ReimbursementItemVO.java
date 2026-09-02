package com.rcdis.agent.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.rcdis.agent.entity.BudgetCategoryEntity;
import com.rcdis.agent.entity.ExpenseRecordEntity;
import com.rcdis.agent.entity.ReimbursementItemEntity;

/**
 * Snapshot of a reimbursement line item backed by an expense record.
 */
public record ReimbursementItemVO(
        Long itemId,
        Long expenseId,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal amount,
        LocalDate expenseDate,
        String vendor,
        String invoiceNo,
        String receiptFile,
        String description,
        Long budgetCategoryId,
        String categoryName,
        String expenseStatus
) {

    public static ReimbursementItemVO from(
            ReimbursementItemEntity item,
            ExpenseRecordEntity expense,
            BudgetCategoryEntity budgetCategory) {
        return new ReimbursementItemVO(
                item.getId(),
                item.getExpenseId(),
                item.getAmount(),
                expense == null ? null : expense.getExpenseDate(),
                expense == null ? null : expense.getVendor(),
                expense == null ? null : expense.getInvoiceNo(),
                expense == null ? null : expense.getReceiptFile(),
                expense == null ? null : expense.getDescription(),
                expense == null ? null : expense.getBudgetCategoryId(),
                budgetCategory == null ? null : budgetCategory.getCategoryName(),
                expense == null ? null : expense.getStatus());
    }
}
