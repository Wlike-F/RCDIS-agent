package com.rcdis.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Records a human decision on one candidate.
 *
 * <p>Approval requires an explicit statement of the expected behaviour: the point of the candidate
 * queue is that a person decides what the Agent <em>should</em> have done, so a promoted case encodes
 * a specification rather than a snapshot of whatever the model happened to do.</p>
 */
public record EvalCandidateReviewRequest(
        @NotBlank @Pattern(regexp = "APPROVE|REJECT", message = "decision 只能是 APPROVE 或 REJECT") String decision,
        @Size(max = 2000) String expectedBehavior,
        @Size(max = 128) String reproduceEnv,
        @Size(max = 500) String note
) {
}
