package com.rcdis.agent.common.context;

import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.rcdis.agent.common.security.JwtPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class RequestContextInterceptor implements HandlerInterceptor {

    // Only the conversation id may arrive via a header; it is request context, not identity.
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
            return CurrentUserTO.of(
                    principal.userId(),
                    principal.username(),
                    principal.tenantId(),
                    request.getHeader(CONVERSATION_ID_HEADER),
                    Set.copyOf(principal.roles()));
        }
        // No verified JWT means no identity. Identity is never taken from client-supplied X-User-*
        // headers, which anyone can forge to impersonate another user or escalate to a higher role.
        return CurrentUserTO.anonymous();
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
