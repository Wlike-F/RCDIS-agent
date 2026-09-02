package com.rcdis.agent.controller;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.ExpenseCreateRequest;
import com.rcdis.agent.dto.ExpenseDeleteRequest;
import com.rcdis.agent.dto.ExpensePageRequest;
import com.rcdis.agent.dto.ExpenseUpdateRequest;
import com.rcdis.agent.service.ExpenseService;
import com.rcdis.agent.vo.ExpenseVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Validated
@Tag(name = "Expenses")
@RestController
@RequestMapping("/api/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;

    @Operation(summary = "Page expenses")
    @GetMapping
    public ApiResponse<PageResponse<ExpenseVO>> pageExpenses(@Valid @ParameterObject ExpensePageRequest request) {
        return ApiResponse.success(expenseService.pageExpenses(request));
    }

    @Operation(summary = "Get expense by id")
    @GetMapping("/{id}")
    public ApiResponse<ExpenseVO> getExpense(@PathVariable Long id) {
        return ApiResponse.success(expenseService.getExpense(id));
    }

    @Operation(summary = "Create expense")
    @PostMapping
    public ApiResponse<ExpenseVO> createExpense(@Valid @RequestBody ExpenseCreateRequest request) {
        return ApiResponse.success(expenseService.createExpense(request));
    }

    @Operation(summary = "Update expense")
    @PutMapping("/{id}")
    public ApiResponse<ExpenseVO> updateExpense(
            @PathVariable Long id,
            @Valid @RequestBody ExpenseUpdateRequest request) {
        return ApiResponse.success(expenseService.updateExpense(id, request));
    }

    @Operation(summary = "Delete expense")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteExpense(
            @PathVariable Long id,
            @Valid @RequestBody ExpenseDeleteRequest request) {
        expenseService.deleteExpense(id, request);
        return ApiResponse.success(null);
    }
}
