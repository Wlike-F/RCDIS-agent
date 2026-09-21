package com.rcdis.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.service.JwtTokenService;
import com.rcdis.agent.support.TestAuth;

/**
 * RBAC integration tests.
 *
 * <p>Covers real login against the seeded accounts and the role-based access decisions on the
 * guarded endpoints. Every request carries an explicit bearer token, so each assertion is about the
 * role presented rather than a shared interceptor.</p>
 */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "rcdis.feishu.enabled=false",
                "rcdis.feishu.client-type=noop",
                "management.endpoints.web.exposure.include=health,prometheus",
                "management.prometheus.metrics.export.enabled=true"
        })
class RbacAuthorizationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void useExplicitPerRequestTokens() {
        // Other test classes that share this cached Spring context install a default ADMIN bearer
        // interceptor on the shared TestRestTemplate. Clear it so every request in this class controls
        // its own Authorization header (a specific role, or none at all for the anonymous case).
        restTemplate.getRestTemplate().getInterceptors().clear();
    }

    // ---------- login ----------

    @Test
    void adminLoginSucceedsAndCarriesAdminRole() throws Exception {
        ResponseEntity<String> response = login("admin", "admin123");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode data = data(response);
        assertThat(data.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(data.get("accessToken").asText()).isNotBlank();
        assertThat(data.get("user").get("roles").toString()).contains("ADMIN");
    }

    @Test
    void loginWithWrongPasswordIsRejected() {
        ResponseEntity<String> response = login("admin", "wrong-password");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("AUTH_LOGIN_FAILED");
    }

    @Test
    void loginWithUnknownUserIsRejected() {
        ResponseEntity<String> response = login("no-such-user", "admin123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("AUTH_LOGIN_FAILED");
    }

    @Test
    void disabledUserCannotLogin() throws Exception {
        String adminToken = TestAuth.adminToken(jwtTokenService);
        String username = "tmp-" + UUID.randomUUID().toString().substring(0, 8);

        ResponseEntity<String> created = postJson("/api/users", adminToken, Map.of(
                "username", username,
                "password", "secret123",
                "displayName", "临时科研人员",
                "tenantId", "test",
                "roles", List.of("RESEARCHER")));
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        long id = data(created).get("id").asLong();

        // An active account can log in.
        assertThat(login(username, "secret123").getStatusCode().is2xxSuccessful()).isTrue();

        // Disabling it through the admin API blocks further logins.
        assertThat(postJson("/api/users/" + id + "/status", adminToken, Map.of())
                .getStatusCode().is2xxSuccessful()).isTrue();
        ResponseEntity<String> disabledLogin = login(username, "secret123");
        assertThat(disabledLogin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(disabledLogin.getBody()).contains("AUTH_USER_DISABLED");
    }

    // ---------- role isolation ----------

    @Test
    void researcherIsBlockedFromAdminAndApprovalEndpoints() {
        String token = TestAuth.researcherToken(jwtTokenService);

        assertThat(get("/api/model-providers", token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/users?current=1&size=10", token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/audit-logs?current=1&size=10", token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void approverCanReadAuditLogsButNotAdminister() {
        String token = TestAuth.approverToken(jwtTokenService);

        assertThat(get("/api/audit-logs?current=1&size=10", token).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/api/model-providers", token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/users?current=1&size=10", token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCanAccessAdminEndpoints() {
        String token = TestAuth.adminToken(jwtTokenService);

        assertThat(get("/api/model-providers", token).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/api/users?current=1&size=10", token).getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void researcherCanReadBusinessData() {
        String token = TestAuth.researcherToken(jwtTokenService);

        assertThat(get("/api/projects?current=1&size=10", token).getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void anonymousIsRejectedFromGuardedEndpoints() {
        // No bearer token at all; the guard must refuse regardless of the exact 401/403 mapping.
        assertThat(get("/api/model-providers", null).getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    void actuatorHealthIsPublicWithoutLeakingDetails() {
        ResponseEntity<String> response = get("/actuator/health", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getBody()).doesNotContain("db", "diskSpace", "components");
    }

    @Test
    void prometheusRequiresAdminRole() {
        assertThat(get("/actuator/prometheus", null).getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(get("/actuator/prometheus", TestAuth.researcherToken(jwtTokenService)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> adminResponse =
                get("/actuator/prometheus", TestAuth.adminToken(jwtTokenService));
        assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminResponse.getBody()).contains("jvm_info");
    }

    @Test
    void researcherCannotCreateProject() {
        // Creating a project is an ADMIN-only write; a RESEARCHER token must be refused at the filter
        // layer before the controller method runs.
        String token = TestAuth.researcherToken(jwtTokenService);
        ResponseEntity<String> response = postJson("/api/projects", token, Map.of(
                "projectCode", "DIAG-" + UUID.randomUUID().toString().substring(0, 8),
                "projectName", "diagnostic project",
                "principalInvestigator", "DIAG PI",
                "fundingSource", "DIAG",
                "totalBudget", "1000.00",
                "startDate", "2026-01-01",
                "endDate", "2029-12-31",
                "status", "ACTIVE",
                "quickMode", false));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------- helpers ----------

    private ResponseEntity<String> login(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(
                "/api/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", password), headers),
                String.class);
    }

    private ResponseEntity<String> get(String url, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> postJson(String url, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("data");
    }
}
