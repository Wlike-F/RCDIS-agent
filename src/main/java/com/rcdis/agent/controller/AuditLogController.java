package com.rcdis.agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.service.AuditLogService;
import com.rcdis.agent.vo.AuditLogVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@Tag(name = "Audit Logs")
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @Operation(summary = "List audit logs")
    @GetMapping
    public ApiResponse<PageResponse<AuditLogVO>> listAuditLogs(
            @RequestParam @Min(1) long current,
            @RequestParam @Min(1) @Max(500) long size
    ) {
        return ApiResponse.success(auditLogService.list(current, size));
    }
}
