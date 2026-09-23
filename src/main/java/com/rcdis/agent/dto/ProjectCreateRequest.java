package com.rcdis.agent.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create-one research project payload.
 *
 * <p>{@code projectCode} is optional: users should not have to invent identifiers; when it is
 * blank the service mints {@code P-<year>-<seq>} automatically. Explicit codes are still honored
 * for imports and legacy integrations.</p>
 */

public record ProjectCreateRequest(
        /** Optional: when blank the backend generates a {@code P-<year>-<seq>} code. */
        @Size(max = 64) String projectCode,
        @NotBlank @Size(max = 255) String projectName,
        @Size(max = 128) String principalInvestigator,
        @Size(max = 128) String fundingSource,
        @NotNull @DecimalMin(value = "0.00") BigDecimal totalBudget,
        LocalDate startDate,
        LocalDate endDate,
        @NotBlank @Pattern(regexp = "ACTIVE|SUSPENDED|CLOSED") String status,
        // Quick mode auto-creates a default budget category equal to totalBudget; null means enabled.
        Boolean quickMode
) {
}
