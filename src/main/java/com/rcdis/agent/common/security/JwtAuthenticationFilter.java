package com.rcdis.agent.common.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.service.JwtTokenService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    /** Spring Security matches {@code hasRole('ADMIN')} against the authority {@code ROLE_ADMIN}. */
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtTokenService jwtTokenService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(bearerToken)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!bearerToken.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response, "AUTHORIZATION_HEADER_INVALID", "Authorization header must use Bearer token");
            return;
        }
        String token = bearerToken.substring(BEARER_PREFIX.length()).trim();
        try {
            JwtPrincipal principal = jwtTokenService.parse(token);
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    principal.roles().stream()
                            .map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
                            .toList());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (RuntimeException exception) {
            SecurityContextHolder.clearContext();
            log.atWarn()
                    .setCause(exception)
                    .log("JWT authentication failed");
            writeUnauthorized(response, "JWT_INVALID", exception.getMessage());
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.failure(code, message));
    }
}
