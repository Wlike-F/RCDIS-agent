package com.rcdis.agent.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
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
        BigDecimal remainingBudget,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        Integer version
) {

    public static ProjectVO fromEntity(ResearchProjectEntity entity) {
        return fromEntity(entity, entity.getTotalBudget());
    }

    public static ProjectVO fromEntity(ResearchProjectEntity entity, BigDecimal remainingBudget) {
        return new ProjectVO(
                entity.getId(),
                entity.getProjectCode(),
                entity.getProjectName(),
                entity.getPrincipalInvestigator(),
                entity.getFundingSource(),
                entity.getTotalBudget(),
                remainingBudget,
                entity.getStartDate(),
                entity.getEndDate(),
                entity.getStatus(),
                entity.getVersion());
    }
}
