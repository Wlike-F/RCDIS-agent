package com.rcdis.agent.controller;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.rcdis.agent.dto.ProjectCreateRequest;
import com.rcdis.agent.dto.ProjectDeleteRequest;
import com.rcdis.agent.dto.ProjectPageRequest;
import com.rcdis.agent.dto.ProjectUpdateRequest;
import com.rcdis.agent.service.ResearchProjectService;
import com.rcdis.agent.vo.ProjectVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Validated
@Tag(name = "Research Projects")
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ResearchProjectService researchProjectService;

    @Operation(summary = "Page research projects")
    @GetMapping
    public ApiResponse<PageResponse<ProjectVO>> pageProjects(@Valid @ParameterObject ProjectPageRequest request) {
        return ApiResponse.success(researchProjectService.pageProjects(request));
    }

    @Operation(summary = "Get research project by id")
    @GetMapping("/{id}")
    public ApiResponse<ProjectVO> getProject(@PathVariable Long id) {
        return ApiResponse.success(researchProjectService.getProject(id));
    }

    @Operation(summary = "Create research project")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ApiResponse<ProjectVO> createProject(@Valid @RequestBody ProjectCreateRequest request) {
        return ApiResponse.success(researchProjectService.createProject(request));
    }

    @Operation(summary = "Update research project")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ApiResponse<ProjectVO> updateProject(
            @PathVariable Long id,
            @Valid @RequestBody ProjectUpdateRequest request) {
        return ApiResponse.success(researchProjectService.updateProject(id, request));
    }

    @Operation(summary = "Delete research project")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteProject(
            @PathVariable Long id,
            @Valid @RequestBody ProjectDeleteRequest request) {
        researchProjectService.deleteProject(id, request);
        return ApiResponse.success(null);
    }
}
