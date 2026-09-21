package com.rcdis.agent.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rcdis.agent.entity.ReimbursementItemEntity;

public interface ReimbursementItemMapper extends BaseMapper<ReimbursementItemEntity> {

    /**
     * Physical delete of all lines of an order. Used when a draft is edited (full line
     * replacement) so removed lines disappear instead of lingering as soft-deleted rows.
     */
    @Delete("delete from reimbursement_item where reimbursement_id = #{reimbursementId}")
    int physicalDeleteByReimbursementId(@Param("reimbursementId") Long reimbursementId);
}
