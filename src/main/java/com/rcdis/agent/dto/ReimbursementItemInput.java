package com.rcdis.agent.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A single reimbursement line entered inline when creating or editing an order.
 * The line itself carries the spend facts; there is no separate expense record.
 */
public record ReimbursementItemInput(
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotNull LocalDate expenseDate,
        @Size(max = 128) String vendor,
        @Size(max = 128) String invoiceNo,
        @Size(max = 255) String receiptFile,
        @NotBlank @Size(max = 500) String description,
        @Size(max = 128) String counterpartyAccount
) {
}
