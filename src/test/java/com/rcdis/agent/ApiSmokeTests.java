package com.rcdis.agent;

import java.math.BigDecimal;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.service.JwtTokenService;
import com.rcdis.agent.support.TestAuth;

@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pin Feishu config: inline test properties outrank OS environment variables,
        // so host-level RCDIS_FEISHU_* settings cannot leak into smoke tests.
        properties = {
                "rcdis.feishu.enabled=false",
                "rcdis.feishu.client-type=noop",
                "rcdis.feishu.app-id=",
                "rcdis.feishu.app-secret=",
                "rcdis.feishu.default-receive-id="
        })
class ApiSmokeTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void authenticateAsAdmin() {
        TestAuth.applyBearer(restTemplate, TestAuth.adminToken(jwtTokenService));
    }

    @Test
    void healthEndpointWorks() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/health", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void modelProviderEndpointWorks() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/model-providers", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"providerId\":\"dashscope\"");
    }

    @Test
    void chatEndpointWorks() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(
                Map.of("message", "查询项目经费余额"), headers);

        ResponseEntity<String> response = restTemplate.postForEntity("/api/chat", request, String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"providerId\":\"dashscope\"");
    }

    @Test
    void authLoginEndpointWorks() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(
                Map.of("username", "admin", "password", "admin123"), headers);

        ResponseEntity<String> response = restTemplate.postForEntity("/api/auth/login", request, String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"tokenType\":\"Bearer\"");
        assertThat(response.getBody()).contains("\"accessToken\"");
    }

    @Test
    void openApiEndpointWorks() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("RCDIS Agent API");
    }

    @Test
    void feishuTestMessageEndpointWorksWithNoopClient() {
        ResponseEntity<String> configResponse = restTemplate.getForEntity("/api/feishu/config", String.class);

        assertThat(configResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(configResponse.getBody()).contains("\"status\":\"DISABLED\"");

        ResponseEntity<String> templateResponse = restTemplate.getForEntity("/api/feishu/templates", String.class);

        assertThat(templateResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(templateResponse.getBody()).contains("budget-warning");

        String idempotencyKey = "feishu-smoke-" + UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", "test-user");
        HttpEntity<Map<String, String>> request = new HttpEntity<>(
                Map.of("text", "Feishu noop smoke test", "idempotencyKey", idempotencyKey), headers);

        ResponseEntity<String> response = restTemplate.postForEntity("/api/feishu/test-message", request, String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"SENT\"");
        assertThat(response.getBody()).contains("\"duplicate\":false");

        ResponseEntity<String> duplicateResponse = restTemplate.postForEntity(
                "/api/feishu/test-message",
                request,
                String.class);

        assertThat(duplicateResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(duplicateResponse.getBody()).contains("\"duplicate\":true");

        ResponseEntity<String> outboxResponse = restTemplate.getForEntity(
                "/api/feishu/notifications?current=1&size=10&keyword=" + idempotencyKey,
                String.class);

        assertThat(outboxResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(outboxResponse.getBody()).contains(idempotencyKey);
        assertThat(outboxResponse.getBody()).contains("\"channel\":\"FEISHU_NOOP\"");

        ResponseEntity<String> auditResponse = restTemplate.getForEntity("/api/audit-logs?current=1&size=10", String.class);

        assertThat(auditResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(auditResponse.getBody()).contains("SEND_TEST_FEISHU_MESSAGE");
    }

    @Test
    void projectCrudWorkflowWorks() throws Exception {
        String uniqueCode = "NSFC-" + UUID.randomUUID();
        ResponseEntity<String> createProjectResponse = restTemplate.postForEntity(
                "/api/projects",
                jsonRequest(Map.of(
                        "projectCode", uniqueCode,
                        "projectName", "Research Fund Test Project",
                        "principalInvestigator", "Test PI",
                        "fundingSource", "NSFC",
                        "totalBudget", new BigDecimal("100000.00"),
                        "startDate", "2026-01-01",
                        "endDate", "2029-12-31",
                        "status", "ACTIVE")),
                String.class);

        assertThat(createProjectResponse.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode project = data(createProjectResponse);
        long projectId = project.get("id").asLong();
        int projectVersion = project.get("version").asInt();
        assertThat(project.get("availableAmount").asText()).isEqualTo("100000.00");

        ResponseEntity<String> projectPageResponse = restTemplate.getForEntity(
                "/api/projects?current=1&size=20&keyword=" + uniqueCode + "&status=ACTIVE",
                String.class);

        assertThat(projectPageResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(projectPageResponse.getBody()).contains(uniqueCode);

        ResponseEntity<String> updateProjectResponse = restTemplate.exchange(
                "/api/projects/" + projectId,
                HttpMethod.PUT,
                jsonRequest(Map.of(
                        "projectName", "Research Fund Test Project Updated",
                        "principalInvestigator", "Test PI",
                        "fundingSource", "NSFC",
                        "totalBudget", new BigDecimal("120000.00"),
                        "startDate", "2026-01-01",
                        "endDate", "2029-12-31",
                        "status", "ACTIVE",
                        "version", projectVersion)),
                String.class);

        assertThat(updateProjectResponse.getStatusCode().is2xxSuccessful()).isTrue();
        int updatedProjectVersion = data(updateProjectResponse).get("version").asInt();

        ResponseEntity<String> deleteProjectResponse = restTemplate.exchange(
                "/api/projects/" + projectId,
                HttpMethod.DELETE,
                jsonRequest(Map.of(
                        "reason", "Smoke test cleanup project",
                        "version", updatedProjectVersion)),
                String.class);

        assertThat(deleteProjectResponse.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<String> auditResponse = restTemplate.getForEntity("/api/audit-logs?current=1&size=20", String.class);

        assertThat(auditResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(auditResponse.getBody()).contains("CREATE_RESEARCH_PROJECT");
        assertThat(auditResponse.getBody()).contains("DELETE_RESEARCH_PROJECT");
        assertThat(auditResponse.getBody()).contains("Smoke test cleanup project");
    }

    private HttpEntity<Map<String, Object>> jsonRequest(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", "test-user");
        headers.set("X-User-Name", "Test User");
        headers.set("X-Tenant-Id", "test");
        return new HttpEntity<>(body, headers);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("data");
    }
}
