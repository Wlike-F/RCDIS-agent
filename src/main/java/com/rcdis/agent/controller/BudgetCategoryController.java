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
import com.rcdis.agent.dto.BudgetCategoryCreateRequest;
import com.rcdis.agent.dto.BudgetCategoryDeleteRequest;
import com.rcdis.agent.dto.BudgetCategoryPageRequest;
import com.rcdis.agent.dto.BudgetCategoryUpdateRequest;
import com.rcdis.agent.service.BudgetCategoryService;
import com.rcdis.agent.vo.BudgetCategoryVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Validated
@Tag(name = "Budget Categories")
@RestController
@RequestMapping("/api/projects/{projectId}/budget-categories")
@RequiredArgsConstructor
public class BudgetCategoryController {

    private final BudgetCategoryService budgetCategoryService;

    @Operation(summary = "Page budget categories for a project")
    @GetMapping
    public ApiResponse<PageResponse<BudgetCategoryVO>> pageBudgetCategories(
            @PathVariable Long projectId,
            @Valid @ParameterObject BudgetCategoryPageRequest request) {
        return ApiResponse.success(budgetCategoryService.pageBudgetCategories(projectId, request));
    }

    @Operation(summary = "Get budget category by id")
    @GetMapping("/{id}")
    public ApiResponse<BudgetCategoryVO> getBudgetCategory(
            @PathVariable Long projectId,
            @PathVariable Long id) {
        return ApiResponse.success(budgetCategoryService.getBudgetCategory(projectId, id));
    }

    @Operation(summary = "Create budget category")
    @PostMapping
    public ApiResponse<BudgetCategoryVO> createBudgetCategory(
            @PathVariable Long projectId,
            @Valid @RequestBody BudgetCategoryCreateRequest request) {
        return ApiResponse.success(budgetCategoryService.createBudgetCategory(projectId, request));
    }

    @Operation(summary = "Update budget category")
    @PutMapping("/{id}")
    public ApiResponse<BudgetCategoryVO> updateBudgetCategory(
            @PathVariable Long projectId,
            @PathVariable Long id,
            @Valid @RequestBody BudgetCategoryUpdateRequest request) {
        return ApiResponse.success(budgetCategoryService.updateBudgetCategory(projectId, id, request));
    }

    @Operation(summary = "Delete budget category")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteBudgetCategory(
            @PathVariable Long projectId,
            @PathVariable Long id,
            @Valid @RequestBody BudgetCategoryDeleteRequest request) {
        budgetCategoryService.deleteBudgetCategory(projectId, id, request);
        return ApiResponse.success(null);
    }
}
