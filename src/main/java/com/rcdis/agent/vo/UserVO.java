package com.rcdis.agent.vo;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * User account as shown in the administration page.
 *
 * <p>The password hash is deliberately absent; it is never exposed to the frontend.</p>
 */
public record UserVO(
        Long id,
        String username,
        String displayName,
        String tenantId,
        String status,
        List<String> roles,
        OffsetDateTime lastLoginAt,
        OffsetDateTime createdAt,
        Integer version
) {
}
