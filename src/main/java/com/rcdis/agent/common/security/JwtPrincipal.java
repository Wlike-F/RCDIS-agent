package com.rcdis.agent.common.security;

import java.util.List;

public record JwtPrincipal(
        String userId,
        String username,
        String tenantId,
        List<String> roles
) {

    public JwtPrincipal {
        roles = List.copyOf(roles);
    }
}
