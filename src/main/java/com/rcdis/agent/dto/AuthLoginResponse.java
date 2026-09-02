package com.rcdis.agent.dto;

import java.time.OffsetDateTime;

import com.rcdis.agent.vo.AuthUserVO;

public record AuthLoginResponse(
        String accessToken,
        String tokenType,
        OffsetDateTime expiresAt,
        AuthUserVO user
) {
}
