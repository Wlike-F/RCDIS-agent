package com.rcdis.agent.service.impl;

import java.time.OffsetDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.common.security.JwtPrincipal;
import com.rcdis.agent.config.SecurityProperties;
import com.rcdis.agent.dto.AuthLoginRequest;
import com.rcdis.agent.dto.AuthLoginResponse;
import com.rcdis.agent.service.AuthService;
import com.rcdis.agent.service.JwtTokenService;
import com.rcdis.agent.vo.AuthUserVO;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String TOKEN_TYPE_BEARER = "Bearer";

    private final SecurityProperties securityProperties;
    private final JwtTokenService jwtTokenService;

    @Override
    public AuthLoginResponse login(AuthLoginRequest request) {
        SecurityProperties.DevUser devUser = securityProperties.getDevUser();
        validateDevUser(devUser);
        if (!request.username().equals(devUser.getUsername()) || !request.password().equals(devUser.getPassword())) {
            throw new BusinessException(
                    "AUTH_LOGIN_FAILED",
                    "Username or password is incorrect",
                    HttpStatus.UNAUTHORIZED);
        }
        JwtPrincipal principal = new JwtPrincipal(
                devUser.getUserId(),
                devUser.getUsername(),
                devUser.getTenantId(),
                devUser.getRoles());
        OffsetDateTime issuedAt = OffsetDateTime.now();
        OffsetDateTime expiresAt = issuedAt.plusMinutes(securityProperties.getJwt().getAccessTokenTtlMinutes());
        String token = jwtTokenService.issue(principal, issuedAt, expiresAt);
        AuthUserVO user = new AuthUserVO(
                principal.userId(),
                principal.username(),
                principal.tenantId(),
                principal.roles());
        return new AuthLoginResponse(token, TOKEN_TYPE_BEARER, expiresAt, user);
    }

    private void validateDevUser(SecurityProperties.DevUser devUser) {
        if (!devUser.isEnabled()) {
            throw new BusinessException(
                    "AUTH_DEV_USER_DISABLED",
                    "Development login user is disabled",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (!StringUtils.hasText(devUser.getUserId())
                || !StringUtils.hasText(devUser.getUsername())
                || !StringUtils.hasText(devUser.getPassword())
                || !StringUtils.hasText(devUser.getTenantId())) {
            throw new BusinessException(
                    "AUTH_DEV_USER_NOT_CONFIGURED",
                    "Development login user configuration is incomplete",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
