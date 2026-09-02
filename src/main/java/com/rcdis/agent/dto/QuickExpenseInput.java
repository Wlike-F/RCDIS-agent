package com.rcdis.agent.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Quick expense entry embedded in reimbursement creation: the expense record is
 * registered through ExpenseService first (budget validation + CREATE_EXPENSE audit),
 * then linked to the order in the same transaction.
 */
public record QuickExpenseInput(
        @NotNull Long budgetCategoryId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotNull LocalDate expenseDate,
        @NotBlank @Size(max = 500) String description,
        @Size(max = 128) String vendor,
        @Size(max = 128) String invoiceNo,
        @Size(max = 255) String receiptFile
) {
}
