package com.rcdis.agent.service;

import java.util.List;

import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.ReimbursementActionRequest;
import com.rcdis.agent.dto.ReimbursementCreateRequest;
import com.rcdis.agent.dto.ReimbursementPageRequest;
import com.rcdis.agent.dto.ReimbursementUpdateRequest;
import com.rcdis.agent.vo.ExpenseVO;
import com.rcdis.agent.vo.MaterialCheckVO;
import com.rcdis.agent.vo.ReimbursementDetailVO;
import com.rcdis.agent.vo.ReimbursementVO;

public interface ReimbursementService {

    PageResponse<ReimbursementVO> pageReimbursements(ReimbursementPageRequest request);

    ReimbursementDetailVO getReimbursement(Long id);

    ReimbursementDetailVO createReimbursement(ReimbursementCreateRequest request);

    /**
     * Expenses eligible for reimbursement: REGISTERED, belonging to the project,
     * and not yet linked to any reimbursement order.
     * When excludeOrderId is provided, items of that order are ignored while
     * computing linked expenses so the edit dialog can show its own expenses.
     */
    List<ExpenseVO> listAvailableExpenses(Long projectId, Long excludeOrderId);

    MaterialCheckVO checkMaterials(Long id);

    ReimbursementDetailVO updateReimbursement(Long id, ReimbursementUpdateRequest request);

    ReimbursementDetailVO voidReimbursement(Long id, ReimbursementActionRequest request);

    ReimbursementDetailVO submitReimbursement(Long id, ReimbursementActionRequest request);

    ReimbursementDetailVO approveReimbursement(Long id, ReimbursementActionRequest request);

    ReimbursementDetailVO rejectReimbursement(Long id, ReimbursementActionRequest request);
}
