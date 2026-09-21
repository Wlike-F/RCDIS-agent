package com.rcdis.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Resets a user's password. An administrator sets a new password directly; the value is hashed with
 * BCrypt before storage.
 */
public record UserPasswordRequest(
        @NotBlank
        @Size(min = 6, max = 64)
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String password
) {
}
