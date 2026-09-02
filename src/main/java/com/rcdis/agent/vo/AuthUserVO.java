package com.rcdis.agent.vo;

import java.util.List;

public record AuthUserVO(
        String userId,
        String username,
        String tenantId,
        List<String> roles
) {

    public AuthUserVO {
        roles = List.copyOf(roles);
    }
}
