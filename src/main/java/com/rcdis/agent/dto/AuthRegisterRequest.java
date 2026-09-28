package com.rcdis.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Self-service sign-up payload. The role is not part of the contract on purpose: registration can
 * only ever mint a researcher account, and an elevated role must be granted afterwards by an admin
 * through the user management page (audited).
 */
public record AuthRegisterRequest(
        @NotBlank
        @Size(min = 3, max = 64)
        @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "username 只能包含字母、数字与 _ . -")
        String username,

        @NotBlank
        @Size(min = 6, max = 64)
        String password,

        @NotBlank
        @Size(max = 128)
        String displayName
) {
}
