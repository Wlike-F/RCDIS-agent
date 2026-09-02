package com.rcdis.agent.service;

import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.ExpenseCreateRequest;
import com.rcdis.agent.dto.ExpenseDeleteRequest;
import com.rcdis.agent.dto.ExpensePageRequest;
import com.rcdis.agent.dto.ExpenseUpdateRequest;
import com.rcdis.agent.vo.ExpenseVO;

public interface ExpenseService {

    PageResponse<ExpenseVO> pageExpenses(ExpensePageRequest request);

    ExpenseVO getExpense(Long id);

    ExpenseVO createExpense(ExpenseCreateRequest request);

    ExpenseVO updateExpense(Long id, ExpenseUpdateRequest request);

    void deleteExpense(Long id, ExpenseDeleteRequest request);
}
