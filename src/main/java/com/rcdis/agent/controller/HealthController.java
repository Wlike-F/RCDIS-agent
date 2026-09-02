package com.rcdis.agent.controller;

import java.time.OffsetDateTime;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.vo.HealthVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Health")
@RestController
@RequestMapping("/api")
public class HealthController {

    @Operation(summary = "Health check")
    @GetMapping("/health")
    public ApiResponse<HealthVO> health() {
        return ApiResponse.success(new HealthVO("UP", "rcdis-agent", OffsetDateTime.now()));
    }
}

