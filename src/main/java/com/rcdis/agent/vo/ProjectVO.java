package com.rcdis.agent.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.rcdis.agent.common.util.MoneyUtils;
import com.rcdis.agent.entity.ResearchProjectEntity;

public record ProjectVO(
        Long id,
        String projectCode,
        String projectName,
        String principalInvestigator,
        String fundingSource,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal totalBudget,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal usedAmount,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal frozenAmount,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal availableAmount,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        Integer version
) {

    public static ProjectVO fromEntity(ResearchProjectEntity entity) {
        BigDecimal used = nz(entity.getUsedAmount());
        BigDecimal frozen = nz(entity.getFrozenAmount());
        BigDecimal available = MoneyUtils.subtract(
                MoneyUtils.subtract(MoneyUtils.normalize(entity.getTotalBudget()), used), frozen);
        return new ProjectVO(
                entity.getId(),
                entity.getProjectCode(),
                entity.getProjectName(),
                entity.getPrincipalInvestigator(),
                entity.getFundingSource(),
                entity.getTotalBudget(),
                used,
                frozen,
                available,
                entity.getStartDate(),
                entity.getEndDate(),
                entity.getStatus(),
                entity.getVersion());
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
