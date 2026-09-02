package com.rcdis.agent.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("expense_record")
public class ExpenseRecordEntity extends BaseEntity {

    private Long projectId;
    private Long budgetCategoryId;
    private BigDecimal amount;
    private LocalDate expenseDate;
    private String vendor;
    private String invoiceNo;
    private String receiptFile;
    private String description;
    private String status;

    @Version
    private Integer version;
}
