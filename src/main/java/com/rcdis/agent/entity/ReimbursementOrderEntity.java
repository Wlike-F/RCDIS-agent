package com.rcdis.agent.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("reimbursement_order")
public class ReimbursementOrderEntity extends BaseEntity {

    private String reimbursementNo;
    private Long projectId;
    private String applicant;
    private BigDecimal totalAmount;
    private String status;
    private OffsetDateTime submittedAt;
    private OffsetDateTime approvedAt;
    private String rejectReason;

    @Version
    private Integer version;
}
