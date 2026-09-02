package com.rcdis.agent.service;

import java.time.OffsetDateTime;

import com.rcdis.agent.common.security.JwtPrincipal;

public interface JwtTokenService {

    String issue(JwtPrincipal principal, OffsetDateTime issuedAt, OffsetDateTime expiresAt);

    JwtPrincipal parse(String token);
}
