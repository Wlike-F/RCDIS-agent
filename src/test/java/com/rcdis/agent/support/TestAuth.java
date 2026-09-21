package com.rcdis.agent.support;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.boot.test.web.client.TestRestTemplate;

import com.rcdis.agent.common.security.JwtPrincipal;
import com.rcdis.agent.service.JwtTokenService;

/**
 * Test helper for the RBAC layer.
 *
 * <p>Method-level {@code @PreAuthorize} guards run regardless of the {@code auth-required} URL rule,
 * so integration tests must present a role-bearing JWT. This mints tokens directly through
 * {@link JwtTokenService} (no login round trip) and attaches one to every {@link TestRestTemplate}
 * request via an interceptor.</p>
 */
public final class TestAuth {

    public static final String ADMIN = "ADMIN";
    public static final String APPROVER = "APPROVER";
    public static final String RESEARCHER = "RESEARCHER";

    private TestAuth() {
    }

    /** Issues a signed JWT carrying the given role codes. */
    public static String token(
            JwtTokenService jwtTokenService,
            String userId,
            String tenantId,
            String... roles) {
        OffsetDateTime issuedAt = OffsetDateTime.now();
        return jwtTokenService.issue(
                new JwtPrincipal(userId, userId, tenantId, List.of(roles)),
                issuedAt,
                issuedAt.plusMinutes(30));
    }

    public static String adminToken(JwtTokenService jwtTokenService) {
        return token(jwtTokenService, "test-admin", "test", ADMIN);
    }

    public static String approverToken(JwtTokenService jwtTokenService) {
        return token(jwtTokenService, "test-approver", "test", APPROVER);
    }

    public static String researcherToken(JwtTokenService jwtTokenService) {
        return token(jwtTokenService, "test-researcher", "test", RESEARCHER);
    }

    /**
     * Replaces the underlying RestTemplate interceptors with one that sends the given bearer token on
     * every request. Clearing first keeps repeated {@code @BeforeEach} calls from stacking interceptors.
     */
    public static void applyBearer(TestRestTemplate restTemplate, String token) {
        List<org.springframework.http.client.ClientHttpRequestInterceptor> interceptors =
                restTemplate.getRestTemplate().getInterceptors();
        interceptors.clear();
        interceptors.add((request, body, execution) -> {
            request.getHeaders().setBearerAuth(token);
            return execution.execute(request, body);
        });
    }
}
