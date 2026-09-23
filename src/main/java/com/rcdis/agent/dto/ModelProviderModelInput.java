package com.rcdis.agent.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One model entry supplied when creating or updating a provider.
 *
 * <p>{@code id} is null for new rows and set for rows that should be updated in place. Rows present
 * in the database but absent from the request are soft deleted.</p>
 */
public record ModelProviderModelInput(
        Long id,
        @NotBlank @Size(max = 128) String modelName,
        @Size(max = 128) String displayName,
        @DecimalMin(value = "0", message = "temperature 不能小于 0")
        @DecimalMax(value = "2", message = "temperature 不能大于 2")
        BigDecimal temperature,
        @Min(value = 1, message = "maxTokens 必须大于 0")
        @Max(value = 32768, message = "maxTokens 不能大于 32768")
        Integer maxTokens,
        Boolean defaultModel,
        @Pattern(regexp = "ACTIVE|DISABLED", message = "status 只能是 ACTIVE 或 DISABLED") String status,
        @Pattern(regexp = "TEXT|VISION|EMBEDDING", message = "capability 只能是 TEXT/VISION/EMBEDDING")
        String capability,
        @Size(max = 500) String remark
) {
}
