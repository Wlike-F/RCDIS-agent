package com.rcdis.agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.service.OverviewService;
import com.rcdis.agent.vo.OverviewSummaryVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Role-aware dashboard overview; available to every authenticated role. */
@Tag(name = "Overview")
@RestController
@RequestMapping("/api/overview")
@RequiredArgsConstructor
public class OverviewController {

    private final OverviewService overviewService;

    @Operation(summary = "Role-aware dashboard overview (admin: global, researcher: personal)")
    @GetMapping("/summary")
    public ApiResponse<OverviewSummaryVO> summary() {
        return ApiResponse.success(
                overviewService.summary(CurrentUserContextHolder.currentOrAnonymous()));
    }
}
