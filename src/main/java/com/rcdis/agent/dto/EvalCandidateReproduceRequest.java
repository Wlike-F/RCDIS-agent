package com.rcdis.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Evidence that an approved candidate was reproduced in an isolated environment before promotion.
 */
public record EvalCandidateReproduceRequest(
        @NotBlank @Size(max = 128) String reproduceEnv,
        @Size(max = 64) String reproduceRunId,
        @Size(max = 500) String note
) {
}
