package com.rcdis.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReimbursementPageRequest(
        @Min(1) long current,
        @Min(1) @Max(500) long size,
        Long projectId,
        @Pattern(regexp = "DRAFT|SUBMITTED|APPROVED|REJECTED") String status,
        @Size(max = 128) String keyword
) {
}
