package com.rcdis.agent.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rcdis.agent.entity.ReimbursementOrderEntity;

public interface ReimbursementOrderMapper extends BaseMapper<ReimbursementOrderEntity> {

    /**
     * Page reimbursement orders with explicit filters.
     * See mapper XML for the query definition.
     */
    Page<ReimbursementOrderEntity> selectReimbursementPage(
            Page<ReimbursementOrderEntity> page,
            @Param("projectId") Long projectId,
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("applicant") String applicant);

    /**
     * Next value of the reimbursement number sequence, used to build unique order numbers.
     */
    @Select("select nextval('reimbursement_no_seq')")
    Long nextReimbursementNoSequence();
}
