package com.rcdis.agent.entity;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("reimbursement_item")
public class ReimbursementItemEntity extends BaseEntity {

    private Long reimbursementId;
    private Long expenseId;
    private BigDecimal amount;
}
