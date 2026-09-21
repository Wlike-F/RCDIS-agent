package com.rcdis.agent.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * Replaces the full set of roles assigned to a user. Each code must be one of the built-in roles
 * (ADMIN, APPROVER, RESEARCHER); invalid codes are rejected by the service layer.
 */
public record UserRolesRequest(
        @NotEmpty
        List<@NotBlank String> roles
) {
}
