package com.rcdis.agent.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("research_project")
public class ResearchProjectEntity extends BaseEntity {

    private String projectCode;
    private String projectName;
    private String principalInvestigator;
    private String fundingSource;
    private BigDecimal totalBudget;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;

    @Version
    private Integer version;
}
