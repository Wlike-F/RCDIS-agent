package com.rcdis.agent.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.service.OcrConfigService;
import com.rcdis.agent.vo.OcrConfigVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Admin maintenance of the receipt OCR configuration (provider/model selection). */
@Tag(name = "Ocr Config")
@RestController
@RequestMapping("/api/admin/ocr-config")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminOcrConfigController {

    private final OcrConfigService ocrConfigService;

    @Operation(summary = "Get the receipt OCR configuration and selectable providers")
    @GetMapping
    public ApiResponse<OcrConfigVO> current() {
        return ApiResponse.success(ocrConfigService.current());
    }

    @Operation(summary = "Update the receipt OCR configuration (null fields keep current values)")
    @PostMapping
    public ApiResponse<OcrConfigVO> update(@RequestBody OcrConfigUpdateRequest request) {
        return ApiResponse.success(ocrConfigService.update(
                request.enabled(), request.providerId(), request.model()));
    }

    public record OcrConfigUpdateRequest(Boolean enabled, String providerId, String model) {
    }
}
