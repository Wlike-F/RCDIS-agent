package com.rcdis.agent.service;

import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.BudgetCategoryCreateRequest;
import com.rcdis.agent.dto.BudgetCategoryDeleteRequest;
import com.rcdis.agent.dto.BudgetCategoryPageRequest;
import com.rcdis.agent.dto.BudgetCategoryUpdateRequest;
import com.rcdis.agent.vo.BudgetCategoryVO;

public interface BudgetCategoryService {

    PageResponse<BudgetCategoryVO> pageBudgetCategories(Long projectId, BudgetCategoryPageRequest request);

    BudgetCategoryVO getBudgetCategory(Long projectId, Long id);

    BudgetCategoryVO createBudgetCategory(Long projectId, BudgetCategoryCreateRequest request);

    BudgetCategoryVO updateBudgetCategory(Long projectId, Long id, BudgetCategoryUpdateRequest request);

    void deleteBudgetCategory(Long projectId, Long id, BudgetCategoryDeleteRequest request);
}
