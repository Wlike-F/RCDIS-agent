package com.rcdis.agent.to;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

/**
 * A single expense line joined with its owning order and project, as read by the expense query
 * tools. Expenses live in {@code reimbursement_item}; this view flattens the three tables.
 */
@Getter
@Setter
public class ExpenseItemRowTO {

    private Long itemId;
    private String reimbursementNo;
    private String projectCode;
    private String projectName;
    private String applicant;
    private String orderStatus;
    private String paymentType;
    private BigDecimal amount;
    private LocalDate expenseDate;
    private String vendor;
    private String invoiceNo;
    private String description;
}
