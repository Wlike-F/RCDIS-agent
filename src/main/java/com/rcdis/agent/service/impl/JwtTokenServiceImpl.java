package com.rcdis.agent.service.impl;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.common.security.JwtPrincipal;
import com.rcdis.agent.config.SecurityProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class JwtTokenServiceImpl implements com.rcdis.agent.service.JwtTokenService {

    private static final String USERNAME_CLAIM = "username";
    private static final String TENANT_ID_CLAIM = "tenantId";
    private static final String ROLES_CLAIM = "roles";

    private final SecurityProperties securityProperties;

    @Override
    public String issue(JwtPrincipal principal, OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
        validateJwtConfiguration();
        return Jwts.builder()
                .issuer(securityProperties.getJwt().getIssuer())
                .subject(principal.userId())
                .claim(USERNAME_CLAIM, principal.username())
                .claim(TENANT_ID_CLAIM, principal.tenantId())
                .claim(ROLES_CLAIM, principal.roles())
                .issuedAt(Date.from(issuedAt.toInstant()))
                .expiration(Date.from(expiresAt.toInstant()))
                .signWith(signingKey(), Jwts.SIG.HS256)
                .compact();
    }

    @Override
    public JwtPrincipal parse(String token) {
        validateJwtConfiguration();
        if (!StringUtils.hasText(token)) {
            throw new BusinessException("JWT_EMPTY", "JWT token must not be blank", HttpStatus.UNAUTHORIZED);
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .requireIssuer(securityProperties.getJwt().getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new JwtPrincipal(
                    claims.getSubject(),
                    claims.get(USERNAME_CLAIM, String.class),
                    claims.get(TENANT_ID_CLAIM, String.class),
                    parseRoles(claims));
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BusinessException("JWT_INVALID", "JWT token is invalid", HttpStatus.UNAUTHORIZED, exception);
        }
    }

    private void validateJwtConfiguration() {
        if (!StringUtils.hasText(securityProperties.getJwt().getIssuer())) {
            throw new BusinessException(
                    "JWT_ISSUER_NOT_CONFIGURED",
                    "JWT issuer is required",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (!StringUtils.hasText(securityProperties.getJwt().getSecret())) {
            throw new BusinessException(
                    "JWT_SECRET_NOT_CONFIGURED",
                    "JWT secret is required",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (securityProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new BusinessException(
                    "JWT_SECRET_TOO_SHORT",
                    "JWT secret must contain at least 32 bytes",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (securityProperties.getJwt().getAccessTokenTtlMinutes() < 1) {
            throw new BusinessException(
                    "JWT_TTL_NOT_CONFIGURED",
                    "JWT access token TTL must be greater than 0",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(securityProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    private List<String> parseRoles(Claims claims) {
        Object roles = claims.get(ROLES_CLAIM);
        if (roles instanceof List<?> roleValues) {
            return roleValues.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }
}
