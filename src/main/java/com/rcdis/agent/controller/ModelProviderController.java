package com.rcdis.agent.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.dto.ModelProviderCreateRequest;
import com.rcdis.agent.dto.ModelProviderDeleteRequest;
import com.rcdis.agent.dto.ModelProviderDiscoveryRequest;
import com.rcdis.agent.dto.ModelProviderTestRequest;
import com.rcdis.agent.dto.ModelProviderTestResponse;
import com.rcdis.agent.dto.ModelProviderUpdateRequest;
import com.rcdis.agent.service.ModelProviderService;
import com.rcdis.agent.vo.ModelProtocolVO;
import com.rcdis.agent.vo.ModelProviderDiscoveryVO;
import com.rcdis.agent.vo.ModelProviderVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Model provider registry endpoints.
 *
 * <p>API keys are accepted on write and are never returned: responses only carry
 * {@code apiKeyConfigured} and a masked {@code apiKeyHint}.</p>
 */
@Tag(name = "Model Providers")
@RestController
@RequestMapping("/api/model-providers")
@RequiredArgsConstructor
public class ModelProviderController {

    private final ModelProviderService modelProviderService;

    @Operation(summary = "List model providers with their models")
    @GetMapping
    public ApiResponse<List<ModelProviderVO>> listProviders() {
        return ApiResponse.success(modelProviderService.listProviders());
    }

    @Operation(summary = "List the wire protocols a custom endpoint may implement")
    @GetMapping("/protocols")
    public ApiResponse<List<ModelProtocolVO>> listProtocols() {
        return ApiResponse.success(modelProviderService.listProtocols());
    }

    @Operation(summary = "Get one model provider")
    @GetMapping("/{id}")
    public ApiResponse<ModelProviderVO> getProvider(@PathVariable Long id) {
        return ApiResponse.success(modelProviderService.getProvider(id));
    }

    @Operation(summary = "Register a custom model provider")
    @PostMapping
    public ApiResponse<ModelProviderVO> createProvider(@Valid @RequestBody ModelProviderCreateRequest request) {
        return ApiResponse.success(modelProviderService.createProvider(request));
    }

    @Operation(summary = "Update a model provider and its model list")
    @PutMapping("/{id}")
    public ApiResponse<ModelProviderVO> updateProvider(
            @PathVariable Long id,
            @Valid @RequestBody ModelProviderUpdateRequest request) {
        return ApiResponse.success(modelProviderService.updateProvider(id, request));
    }

    @Operation(summary = "Soft delete a model provider")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteProvider(
            @PathVariable Long id,
            @Valid @RequestBody ModelProviderDeleteRequest request) {
        modelProviderService.deleteProvider(id, request);
        return ApiResponse.success(null);
    }

    @Operation(summary = "Enable or disable a model provider")
    @PostMapping("/{id}/status")
    public ApiResponse<ModelProviderVO> toggleProviderStatus(@PathVariable Long id) {
        return ApiResponse.success(modelProviderService.toggleProviderStatus(id));
    }

    @Operation(summary = "Mark a model provider as the default one")
    @PostMapping("/{id}/set-default")
    public ApiResponse<ModelProviderVO> setDefaultProvider(@PathVariable Long id) {
        return ApiResponse.success(modelProviderService.setDefaultProvider(id));
    }

    @Operation(summary = "Mark one model as the default model of a provider")
    @PostMapping("/{id}/models/{modelId}/set-default")
    public ApiResponse<ModelProviderVO> setDefaultModel(@PathVariable Long id, @PathVariable Long modelId) {
        return ApiResponse.success(modelProviderService.setDefaultModel(id, modelId));
    }

    @Operation(summary = "Pull the model catalogue from the provider endpoint")
    @PostMapping("/{id}/discover-models")
    public ApiResponse<ModelProviderDiscoveryVO> discoverModels(
            @PathVariable Long id,
            @RequestBody(required = false) ModelProviderDiscoveryRequest request) {
        return ApiResponse.success(modelProviderService.discoverModels(id, request));
    }

    @Operation(summary = "Probe the provider endpoint for real reachability and credentials")
    @PostMapping("/test")
    public ApiResponse<ModelProviderTestResponse> testProvider(
            @Valid @RequestBody(required = false) ModelProviderTestRequest request) {
        return ApiResponse.success(modelProviderService.testProvider(
                request == null ? new ModelProviderTestRequest(null, null, Boolean.FALSE) : request));
    }
}
