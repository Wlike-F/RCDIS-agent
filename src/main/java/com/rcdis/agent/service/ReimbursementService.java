package com.rcdis.agent.service;

import java.util.List;

import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.ReimbursementActionRequest;
import com.rcdis.agent.dto.ReimbursementCreateRequest;
import com.rcdis.agent.dto.ReimbursementPageRequest;
import com.rcdis.agent.dto.ReimbursementUpdateRequest;
import com.rcdis.agent.vo.MaterialCheckVO;
import com.rcdis.agent.vo.ReimbursementDetailVO;
import com.rcdis.agent.vo.ReimbursementVO;

public interface ReimbursementService {

    PageResponse<ReimbursementVO> pageReimbursements(ReimbursementPageRequest request);

    ReimbursementDetailVO getReimbursement(Long id);

    ReimbursementDetailVO createReimbursement(ReimbursementCreateRequest request);

    MaterialCheckVO checkMaterials(Long id);

    ReimbursementDetailVO updateReimbursement(Long id, ReimbursementUpdateRequest request);

    ReimbursementDetailVO voidReimbursement(Long id, ReimbursementActionRequest request);

    ReimbursementDetailVO submitReimbursement(Long id, ReimbursementActionRequest request);

    /**
     * Withdraws a SUBMITTED order back to DRAFT so the applicant can edit or re-submit it.
     * Only the applicant themself or an ADMIN may withdraw; approval is unaffected because an
     * approver acting on a withdrawn order fails the SUBMITTED status guard.
     */
    ReimbursementDetailVO withdrawReimbursement(Long id, ReimbursementActionRequest request);

    ReimbursementDetailVO approveReimbursement(Long id, ReimbursementActionRequest request);

    ReimbursementDetailVO rejectReimbursement(Long id, ReimbursementActionRequest request);
}
