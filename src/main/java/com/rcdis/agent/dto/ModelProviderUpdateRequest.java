package com.rcdis.agent.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rcdis.agent.common.aop.AuditReasonProvider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Updates a provider. The provider code is immutable and therefore not part of this request.
 *
 * <p>A blank {@code apiKey} keeps the stored key unchanged; set {@code clearApiKey} to remove it.</p>
 */
public record ModelProviderUpdateRequest(
        @NotBlank @Size(max = 128) String name,

        @NotBlank @Size(max = 32) String protocol,

        @NotBlank @Size(max = 512)
        @Pattern(regexp = "^https?://.+", message = "接口地址必须以 http:// 或 https:// 开头")
        String baseUrl,

        @Size(max = 255) String chatCompletionsPath,
        @Size(max = 255) String modelsPath,

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Size(max = 512) String apiKey,

        Boolean clearApiKey,

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

        @Pattern(regexp = "ACTIVE|DISABLED", message = "status 只能是 ACTIVE 或 DISABLED") String status,

        @Valid @Size(max = 50, message = "单个供应商最多配置 50 个模型")
        List<ModelProviderModelInput> models,

        @NotNull Integer version,
        @Size(max = 500) String reason
) implements AuditReasonProvider {

    @Override
    public String auditReason() {
        return reason;
    }

    public boolean shouldClearApiKey() {
        return Boolean.TRUE.equals(clearApiKey);
    }

    /** Masks the API key so that it can never leak through logging. */
    @Override
    public String toString() {
        return "ModelProviderUpdateRequest[name=" + name
                + ", protocol=" + protocol
                + ", baseUrl=" + baseUrl
                + ", modelCount=" + (models == null ? 0 : models.size())
                + ", version=" + version
                + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "unchanged" : "masked") + "]";
    }
}
