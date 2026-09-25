package com.rcdis.agent.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.service.EmbeddingConfigService;
import com.rcdis.agent.vo.EmbeddingConfigVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Admin maintenance of the semantic-memory embedding configuration (provider/model/path/switch). */
@Tag(name = "Embedding Config")
@RestController
@RequestMapping("/api/admin/embedding-config")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminEmbeddingConfigController {

    private final EmbeddingConfigService embeddingConfigService;

    @Operation(summary = "Get the embedding configuration and selectable providers")
    @GetMapping
    public ApiResponse<EmbeddingConfigVO> current() {
        return ApiResponse.success(embeddingConfigService.current());
    }

    @Operation(summary = "Update the embedding configuration (null fields keep current values)")
    @PostMapping
    public ApiResponse<EmbeddingConfigVO> update(@RequestBody EmbeddingConfigUpdateRequest request) {
        return ApiResponse.success(embeddingConfigService.update(
                request.enabled(), request.providerId(), request.model(), request.embeddingsPath()));
    }

    public record EmbeddingConfigUpdateRequest(
            Boolean enabled, String providerId, String model, String embeddingsPath) {
    }
}
