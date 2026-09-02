package com.rcdis.agent.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registers a custom model provider.
 *
 * <p>{@code apiKey} is write-only: it is accepted from the request body, encrypted before it reaches
 * the database, and is excluded from every serialized form of this record.</p>
 */
public record ModelProviderCreateRequest(
        @NotBlank @Size(max = 64)
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9._-]*$",
                message = "供应商 ID 需以字母开头，只能包含字母、数字、点、下划线和中划线")
        String providerId,

        @NotBlank @Size(max = 128) String name,

        @NotBlank @Size(max = 32) String protocol,

        @NotBlank @Size(max = 512)
        @Pattern(regexp = "^https?://.+", message = "接口地址必须以 http:// 或 https:// 开头")
        String baseUrl,

        @Size(max = 255) String chatCompletionsPath,
        @Size(max = 255) String modelsPath,

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Size(max = 512) String apiKey,

        @Min(value = 3, message = "超时时间不能小于 3 秒")
        @Max(value = 180, message = "超时时间不能大于 180 秒")
        Integer timeoutSeconds,

        @DecimalMin(value = "0", message = "temperature 不能小于 0")
        @DecimalMax(value = "2", message = "temperature 不能大于 2")
        BigDecimal temperature,

        @Min(value = 1, message = "maxTokens 必须大于 0")
        @Max(value = 32768, message = "maxTokens 不能大于 32768")
        Integer maxTokens,

        @Size(max = 500) String description,
        Boolean enabled,
        Boolean defaultProvider,

        @Valid @Size(max = 50, message = "单个供应商最多配置 50 个模型")
        List<ModelProviderModelInput> models
) {

    /** Masks the API key so that it can never leak through logging. */
    @Override
    public String toString() {
        return "ModelProviderCreateRequest[providerId=" + providerId
                + ", name=" + name
                + ", protocol=" + protocol
                + ", baseUrl=" + baseUrl
                + ", modelCount=" + (models == null ? 0 : models.size())
                + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "unset" : "masked") + "]";
    }
}
