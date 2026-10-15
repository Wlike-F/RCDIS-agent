package com.rcdis.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Marks a reproduced candidate as merged into the version-controlled dataset. */
public record EvalCandidatePromoteRequest(
        @NotBlank @Size(max = 64) String promotedCaseId,
        @Size(max = 500) String note
) {
}
