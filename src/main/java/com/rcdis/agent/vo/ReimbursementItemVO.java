package com.rcdis.agent.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.rcdis.agent.entity.ReimbursementItemEntity;

/**
 * Snapshot of a reimbursement line item; the line carries its own spend facts.
 */
public record ReimbursementItemVO(
        Long itemId,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal amount,
        LocalDate expenseDate,
        String vendor,
        String invoiceNo,
        String receiptFile,
        String description,
        String counterpartyAccount
) {

    public static ReimbursementItemVO from(ReimbursementItemEntity item) {
        return new ReimbursementItemVO(
                item.getId(),
                item.getAmount(),
                item.getExpenseDate(),
                item.getVendor(),
                item.getInvoiceNo(),
                item.getReceiptFile(),
                item.getDescription(),
                item.getCounterpartyAccount());
    }
}
