package com.rcdis.agent.vo;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.rcdis.agent.common.util.MoneyUtils;
import com.rcdis.agent.entity.BudgetCategoryEntity;

public record BudgetCategoryVO(
        Long id,
        Long projectId,
        String categoryCode,
        String categoryName,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal allocatedAmount,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal usedAmount,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal frozenAmount,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal availableAmount,
        String status,
        String remark,
        Integer version
) {

    public static BudgetCategoryVO fromEntity(BudgetCategoryEntity entity) {
        BigDecimal availableAmount = MoneyUtils.subtract(
                MoneyUtils.subtract(entity.getAllocatedAmount(), entity.getUsedAmount()),
                entity.getFrozenAmount());
        return new BudgetCategoryVO(
                entity.getId(),
                entity.getProjectId(),
                entity.getCategoryCode(),
                entity.getCategoryName(),
                entity.getAllocatedAmount(),
                entity.getUsedAmount(),
                entity.getFrozenAmount(),
                availableAmount,
                entity.getStatus(),
                entity.getRemark(),
                entity.getVersion());
    }
}
