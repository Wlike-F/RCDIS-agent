package com.rcdis.agent;

import java.util.List;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.service.UserService;

/**
 * Self-service sign-up: a researcher account is created (never an elevated role), the caller gets a
 * working session immediately, and the password is stored hashed like any admin-provisioned account.
 */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "rcdis.feishu.enabled=false",
                "rcdis.feishu.client-type=noop"
        })
class AuthRegisterTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserService userService;

    @Test
    void registerCreatesResearcherAndReturnsUsableSession() {
        String username = "reg-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<String> response = postRegister(Map.of(
                "username", username,
                "password", "pass1234",
                "displayName", "新注册科研人员"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = data(response);
        assertThat(data.path("accessToken").asText()).isNotBlank();
        assertThat(data.path("user").path("roles")).extracting(JsonNode::asText)
                .containsExactly("RESEARCHER");

        // The returned session authenticates real calls.
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(data.path("accessToken").asText());
        ResponseEntity<String> overview = restTemplate.exchange(
                "/api/overview/summary", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(overview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(overview).path("role").asText()).isEqualTo("MEMBER");

        // Password is stored hashed, never in clear text.
        var stored = userService.findByUsername(username).orElseThrow();
        assertThat(stored.getPasswordHash()).startsWith("$2");
        assertThat(stored.getPasswordHash()).isNotEqualTo("pass1234");
    }

    @Test
    void duplicateUsernameIsRejectedAndRoleCannotBeEscalated() {
        String username = "reg-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(postRegister(Map.of(
                "username", username, "password", "pass1234", "displayName", "首次注册")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // Same username again: conflict surfaced as a business error, no second account.
        ResponseEntity<String> duplicate = postRegister(Map.of(
                "username", username, "password", "other12345", "displayName", "重复注册"));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(userService.findByUsername(username)).isPresent();

        // A payload trying to smuggle in an elevated role is ignored: registration is researcher-only.
        String privileged = "reg-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<String> created = postRegister(Map.of(
                "username", privileged, "password", "pass1234",
                "displayName", "试图提权", "roles", List.of("ADMIN")));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(created).path("user").path("roles")).extracting(JsonNode::asText)
                .containsExactly("RESEARCHER");
    }

    @Test
    void weakPayloadFailsValidation() {
        ResponseEntity<String> shortPassword = postRegister(Map.of(
                "username", "reg-shortpwd", "password", "123", "displayName", "弱密码"));
        assertThat(shortPassword.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> badUsername = postRegister(Map.of(
                "username", "坏用户名", "password", "pass1234", "displayName", "非法用户名"));
        assertThat(badUsername.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<String> postRegister(Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        try {
            return restTemplate.postForEntity(
                    "/api/auth/register", new HttpEntity<>(objectMapper.writeValueAsString(payload), headers),
                    String.class);
        } catch (Exception exception) {
            throw new IllegalStateException("注册请求序列化失败", exception);
        }
    }

    private JsonNode data(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody() == null ? "{}" : response.getBody()).path("data");
        } catch (Exception exception) {
            throw new IllegalStateException("响应不是合法 JSON", exception);
        }
    }
}
