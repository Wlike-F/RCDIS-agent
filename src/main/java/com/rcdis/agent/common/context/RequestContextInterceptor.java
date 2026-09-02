package com.rcdis.agent.common.context;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.rcdis.agent.common.security.JwtPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Slf4j
@Component
public class RequestContextInterceptor implements HandlerInterceptor {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USERNAME_HEADER = "X-User-Name";
    private static final String TENANT_ID_HEADER = "X-Tenant-Id";
    private static final String CONVERSATION_ID_HEADER = "X-Conversation-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        CurrentUserTO currentUser = resolveCurrentUser(request);
        CurrentUserContextHolder.set(currentUser);
        log.atDebug()
                .addKeyValue("userId", currentUser.userId())
                .addKeyValue("tenantId", currentUser.tenantId())
                .addKeyValue("conversationId", currentUser.conversationId())
                .log("Request context initialized");
        return true;
    }

    private CurrentUserTO resolveCurrentUser(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof JwtPrincipal principal) {
            return CurrentUserTO.fromHeaders(
                    principal.userId(),
                    principal.username(),
                    principal.tenantId(),
                    request.getHeader(CONVERSATION_ID_HEADER));
        }
        return CurrentUserTO.fromHeaders(
                request.getHeader(USER_ID_HEADER),
                request.getHeader(USERNAME_HEADER),
                request.getHeader(TENANT_ID_HEADER),
                request.getHeader(CONVERSATION_ID_HEADER));
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception
    ) {
        CurrentUserContextHolder.clear();
    }
}
