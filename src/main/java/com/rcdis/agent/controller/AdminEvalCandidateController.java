package com.rcdis.agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.EvalCandidatePromoteRequest;
import com.rcdis.agent.dto.EvalCandidateReproduceRequest;
import com.rcdis.agent.dto.EvalCandidateReviewRequest;
import com.rcdis.agent.service.EvalCandidateService;
import com.rcdis.agent.vo.EvalCandidateVO;
import com.rcdis.agent.vo.EvalHarvestSummaryVO;

import lombok.RequiredArgsConstructor;

/**
 * Admin-only surface for evaluation candidates discovered from real turns.
 *
 * <p>Guarded by the {@code /api/admin/**} rule in the security configuration. Candidates never
 * reach the dataset by themselves: approval records a human statement of expected behaviour,
 * reproduction records the isolated environment, and only then may a case id be promoted.</p>
 */
@RestController
@RequestMapping("/api/admin/eval-candidates")
@RequiredArgsConstructor
public class AdminEvalCandidateController {

    private final EvalCandidateService evalCandidateService;

    @PostMapping("/harvest")
    public ApiResponse<EvalHarvestSummaryVO> harvest(
            @RequestParam(name = "windowDays", defaultValue = "7") int windowDays) {
        return ApiResponse.success(evalCandidateService.harvest(windowDays));
    }

    @GetMapping
    public ApiResponse<PageResponse<EvalCandidateVO>> page(
            @RequestParam @Min(1) long current,
            @RequestParam @Min(1) @Max(500) long size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "failureType", required = false) String failureType) {
        return ApiResponse.success(evalCandidateService.page(current, size, status, failureType));
    }

    @GetMapping("/{id}")
    public ApiResponse<EvalCandidateVO> get(@PathVariable Long id) {
        return ApiResponse.success(evalCandidateService.get(id));
    }

    @PostMapping("/{id}/review")
    public ApiResponse<EvalCandidateVO> review(@PathVariable Long id,
            @Valid @RequestBody EvalCandidateReviewRequest request) {
        return ApiResponse.success(evalCandidateService.review(id, request));
    }

    @PostMapping("/{id}/reproduce")
    public ApiResponse<EvalCandidateVO> reproduce(@PathVariable Long id,
            @Valid @RequestBody EvalCandidateReproduceRequest request) {
        return ApiResponse.success(evalCandidateService.markReproduced(id, request));
    }

    @PostMapping("/{id}/promote")
    public ApiResponse<EvalCandidateVO> promote(@PathVariable Long id,
            @Valid @RequestBody EvalCandidatePromoteRequest request) {
        return ApiResponse.success(evalCandidateService.promote(id, request.promotedCaseId(), request.note()));
    }

    /** JSONL skeleton of approved candidates; a human finishes the assertions and commits it. */
    @GetMapping(value = "/export", produces = "application/x-ndjson;charset=UTF-8")
    public String export() {
        return evalCandidateService.exportReviewable();
    }
}
