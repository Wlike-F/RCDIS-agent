package com.rcdis.agent.entity;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("budget_category")
public class BudgetCategoryEntity extends BaseEntity {

    private Long projectId;
    private String categoryCode;
    private String categoryName;
    private BigDecimal allocatedAmount;
    private BigDecimal usedAmount;
    private BigDecimal frozenAmount;
    private String status;
    private String remark;

    @Version
    private Integer version;
}
