package com.rcdis.agent;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

/**
 * Notification template management tests: builtin seeding, CRUD,
 * interactive JSON validation and builtin delete protection.
 */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "rcdis.feishu.enabled=false",
                "rcdis.feishu.client-type=noop"
        })
class NotificationTemplateApiTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void builtinTemplatesAreSeededOnStartup() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/feishu/templates", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("budget-warning");
        assertThat(response.getBody()).contains("reimbursement-materials");
        assertThat(response.getBody()).contains("approval-confirmation");
        assertThat(response.getBody()).contains("workflow-status");
        assertThat(response.getBody()).contains("funding-summary");
        assertThat(response.getBody()).contains("REIMBURSEMENT_SUBMITTED");
        assertThat(response.getBody()).contains("REIMBURSEMENT_APPROVED");
        assertThat(response.getBody()).contains("REIMBURSEMENT_REJECTED");
        assertThat(response.getBody()).contains("\"builtin\":true");
    }

    @Test
    void templateCrudValidationAndBuiltinProtectionWork() throws Exception {
        // Create a custom text template.
        String code = "custom-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<String> createResponse = restTemplate.postForEntity(
                "/api/feishu/templates",
                jsonRequest(Map.of(
                        "templateCode", code,
                        "templateName", "自定义测试模板",
                        "scene", "测试",
                        "messageType", "text",
                        "content", "自定义通知：{key}",
                        "reason", "Template CRUD test")),
                String.class);
        assertThat(createResponse.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode created = data(createResponse);
        long templateId = created.get("id").asLong();
        assertThat(created.get("builtin").asBoolean()).isFalse();
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");

        // Duplicate code is refused.
        ResponseEntity<String> duplicateResponse = restTemplate.postForEntity(
                "/api/feishu/templates",
                jsonRequest(Map.of(
                        "templateCode", code,
                        "templateName", "重复编码",
                        "messageType", "text",
                        "content", "dup")),
                String.class);
        assertThat(duplicateResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicateResponse.getBody()).contains("NOTIFICATION_TEMPLATE_CODE_EXISTS");

        // Interactive template with invalid JSON content is refused.
        ResponseEntity<String> invalidJsonResponse = restTemplate.postForEntity(
                "/api/feishu/templates",
                jsonRequest(Map.of(
                        "templateCode", "bad-json-" + UUID.randomUUID().toString().substring(0, 8),
                        "templateName", "非法卡片模板",
                        "messageType", "interactive",
                        "content", "{ not valid json")),
                String.class);
        assertThat(invalidJsonResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalidJsonResponse.getBody()).contains("NOTIFICATION_TEMPLATE_CONTENT_INVALID");

        // Update the custom template with the correct version.
        ResponseEntity<String> updateResponse = restTemplate.exchange(
                "/api/feishu/templates/" + templateId,
                HttpMethod.PUT,
                jsonRequest(Map.of(
                        "templateName", "自定义测试模板-改名",
                        "messageType", "text",
                        "content", "自定义通知 v2：{key}",
                        "version", created.get("version").asInt(),
                        "reason", "Template update test")),
                String.class);
        assertThat(updateResponse.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode updated = data(updateResponse);
        assertThat(updated.get("templateName").asText()).isEqualTo("自定义测试模板-改名");

        // Updating with a stale version is refused.
        ResponseEntity<String> staleUpdate = restTemplate.exchange(
                "/api/feishu/templates/" + templateId,
                HttpMethod.PUT,
                jsonRequest(Map.of(
                        "templateName", "过期版本",
                        "messageType", "text",
                        "content", "stale",
                        "version", created.get("version").asInt())),
                String.class);
        assertThat(staleUpdate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(staleUpdate.getBody()).contains("NOTIFICATION_TEMPLATE_VERSION_CONFLICT");

        // Toggle status disables the template.
        ResponseEntity<String> toggleResponse = restTemplate.postForEntity(
                "/api/feishu/templates/" + templateId + "/toggle-status",
                jsonRequest(Map.of()),
                String.class);
        assertThat(toggleResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(data(toggleResponse).get("status").asText()).isEqualTo("DISABLED");

        // Builtin templates cannot be deleted.
        long builtinId = findBuiltinTemplateId("REIMBURSEMENT_SUBMITTED");
        ResponseEntity<String> builtinDelete = restTemplate.exchange(
                "/api/feishu/templates/" + builtinId,
                HttpMethod.DELETE,
                jsonRequest(Map.of("reason", "尝试删除内置模板")),
                String.class);
        assertThat(builtinDelete.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(builtinDelete.getBody()).contains("NOTIFICATION_TEMPLATE_BUILTIN_PROTECTED");

        // The custom template can be deleted with a reason.
        ResponseEntity<String> deleteResponse = restTemplate.exchange(
                "/api/feishu/templates/" + templateId,
                HttpMethod.DELETE,
                jsonRequest(Map.of("reason", "测试删除自定义模板")),
                String.class);
        assertThat(deleteResponse.getStatusCode().is2xxSuccessful()).isTrue();

        // Template mutations are audited.
        ResponseEntity<String> auditResponse = restTemplate.getForEntity(
                "/api/audit-logs?current=1&size=50", String.class);
        assertThat(auditResponse.getBody()).contains("CREATE_NOTIFICATION_TEMPLATE");
        assertThat(auditResponse.getBody()).contains("UPDATE_NOTIFICATION_TEMPLATE");
        assertThat(auditResponse.getBody()).contains("DELETE_NOTIFICATION_TEMPLATE");
    }

    private long findBuiltinTemplateId(String templateCode) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/feishu/templates", String.class);
        JsonNode templates = data(response);
        for (JsonNode template : templates) {
            if (templateCode.equals(template.get("templateCode").asText())) {
                return template.get("id").asLong();
            }
        }
        throw new IllegalStateException("Builtin template not found: " + templateCode);
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
