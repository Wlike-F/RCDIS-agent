package com.rcdis.agent.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Creates a login account. The plaintext password is accepted here, hashed with BCrypt before it is
 * stored, and never returned by any endpoint.
 */
public record UserCreateRequest(
        @NotBlank
        @Size(min = 3, max = 64)
        @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "username 只能包含字母、数字与 _ . -")
        String username,

        @NotBlank
        @Size(min = 6, max = 64)
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String password,

        @NotBlank
        @Size(max = 128)
        String displayName,

        @Size(max = 64)
        String tenantId,

        @NotEmpty
        List<@NotBlank String> roles
) {
}
