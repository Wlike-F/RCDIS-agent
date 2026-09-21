package com.rcdis.agent.service.impl;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.common.security.JwtPrincipal;
import com.rcdis.agent.config.SecurityProperties;
import com.rcdis.agent.dto.AuthLoginRequest;
import com.rcdis.agent.dto.AuthLoginResponse;
import com.rcdis.agent.entity.SysUserEntity;
import com.rcdis.agent.service.AuthService;
import com.rcdis.agent.service.JwtTokenService;
import com.rcdis.agent.service.UserService;
import com.rcdis.agent.vo.AuthUserVO;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String TOKEN_TYPE_BEARER = "Bearer";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final SecurityProperties securityProperties;
    private final JwtTokenService jwtTokenService;
    private final UserService userService;

    @Override
    public AuthLoginResponse login(AuthLoginRequest request) {
        SysUserEntity user = userService.findByUsername(request.username())
                .orElseThrow(() -> new BusinessException(
                        "AUTH_LOGIN_FAILED",
                        "Username or password is incorrect",
                        HttpStatus.UNAUTHORIZED));
        if (!STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BusinessException(
                    "AUTH_USER_DISABLED",
                    "User account is disabled",
                    HttpStatus.FORBIDDEN);
        }
        if (!userService.isPasswordValid(user, request.password())) {
            throw new BusinessException(
                    "AUTH_LOGIN_FAILED",
                    "Username or password is incorrect",
                    HttpStatus.UNAUTHORIZED);
        }

        List<String> roles = userService.findRoleCodes(user.getId());
        String displayName = StringUtils.hasText(user.getDisplayName())
                ? user.getDisplayName()
                : user.getUsername();
        // userId carries the stable login name so audit_log.actor and created_by stay readable;
        // username carries the display name for the frontend.
        JwtPrincipal principal = new JwtPrincipal(
                user.getUsername(),
                displayName,
                user.getTenantId(),
                roles);

        OffsetDateTime issuedAt = OffsetDateTime.now();
        OffsetDateTime expiresAt = issuedAt.plusMinutes(securityProperties.getJwt().getAccessTokenTtlMinutes());
        String accessToken = jwtTokenService.issue(principal, issuedAt, expiresAt);
        userService.touchLastLogin(user.getId());

        AuthUserVO userVO = new AuthUserVO(
                principal.userId(),
                principal.username(),
                principal.tenantId(),
                principal.roles());
        return new AuthLoginResponse(accessToken, TOKEN_TYPE_BEARER, expiresAt, userVO);
    }
}
