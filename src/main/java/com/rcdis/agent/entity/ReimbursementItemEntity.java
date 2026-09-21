package com.rcdis.agent.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("reimbursement_item")
public class ReimbursementItemEntity extends BaseEntity {

    private Long reimbursementId;
    private BigDecimal amount;
    private LocalDate expenseDate;
    private String vendor;
    private String invoiceNo;
    private String receiptFile;
    private String description;
    private String counterpartyAccount;
}
