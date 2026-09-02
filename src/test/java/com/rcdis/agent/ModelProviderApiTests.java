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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.entity.ModelProviderEntity;
import com.rcdis.agent.infrastructure.ai.ChatModelFactory;
import com.rcdis.agent.infrastructure.ai.ProviderSecretCipher;
import com.rcdis.agent.mapper.ModelProviderMapper;
import com.rcdis.agent.service.ModelProviderService;
import com.rcdis.agent.to.ModelEndpointTO;

/**
 * Model provider registry tests: seeding from configuration, CRUD with optimistic locking,
 * API key encryption at rest, protocol validation, real connectivity probing and chat model building.
 */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "rcdis.feishu.enabled=false",
                "rcdis.feishu.client-type=noop"
        })
class ModelProviderApiTests {

    /** Closed port, so a real connection attempt is refused immediately. */
    private static final String UNREACHABLE_BASE_URL = "http://127.0.0.1:1";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ModelProviderMapper modelProviderMapper;

    @Autowired
    private ProviderSecretCipher providerSecretCipher;

    @Autowired
    private ModelProviderService modelProviderService;

    @Autowired
    private ChatModelFactory chatModelFactory;

    @Test
    void builtinProvidersAreSeededFromConfiguration() throws Exception {
        JsonNode providers = data(restTemplate.getForEntity("/api/model-providers", String.class));

        JsonNode dashscope = findProvider(providers, "dashscope");
        assertThat(dashscope).isNotNull();
        assertThat(dashscope.get("builtin").asBoolean()).isTrue();
        assertThat(dashscope.get("defaultProvider").asBoolean()).isTrue();
        assertThat(dashscope.get("protocol").asText()).isEqualTo("openai-compatible");
        assertThat(dashscope.get("chatModel").asText()).isEqualTo("qwen-plus");
        assertThat(dashscope.get("models")).hasSize(1);
        assertThat(dashscope.get("models").get(0).get("defaultModel").asBoolean()).isTrue();

        JsonNode ollama = findProvider(providers, "local-ollama");
        assertThat(ollama).isNotNull();
        assertThat(ollama.get("enabled").asBoolean()).isFalse();
        // The base URL must stay the service root, otherwise /v1 would be duplicated in the request path.
        assertThat(ollama.get("baseUrl").asText()).doesNotEndWith("/v1");

        // No key material is ever serialized, regardless of what the host environment provides.
        assertThat(dashscope.has("apiKeyCipher")).isFalse();
        assertThat(dashscope.has("apiKey")).isFalse();
        JsonNode hint = dashscope.get("apiKeyHint");
        assertThat(hint.isNull() || hint.asText().startsWith("****")).isTrue();
    }

    @Test
    void protocolCatalogueDocumentsSupportedAndUnsupportedProtocols() throws Exception {
        JsonNode protocols = data(restTemplate.getForEntity("/api/model-providers/protocols", String.class));

        assertThat(protocols).hasSize(3);
        assertThat(findProtocol(protocols, "openai-compatible").get("supported").asBoolean()).isTrue();
        assertThat(findProtocol(protocols, "openai-compatible").get("chatCompletionsPath").asText())
                .isEqualTo("/v1/chat/completions");
        assertThat(findProtocol(protocols, "anthropic").get("supported").asBoolean()).isFalse();
        assertThat(findProtocol(protocols, "ollama-native").get("supported").asBoolean()).isFalse();
        assertThat(findProtocol(protocols, "ollama-native").get("requiresApiKey").asBoolean()).isFalse();
    }

    @Test
    void customProviderLifecyclePersistsToDatabase() throws Exception {
        String code = "test-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = data(restTemplate.postForEntity(
                "/api/model-providers",
                jsonRequest(Map.of(
                        "providerId", code,
                        "name", "临时测试供应商",
                        "protocol", "openai-compatible",
                        "baseUrl", "https://example.invalid/api",
                        "timeoutSeconds", 10,
                        "description", "由集成测试创建",
                        "enabled", true,
                        "models", List.of(
                                Map.of("modelName", "test-model-a", "defaultModel", true, "status", "ACTIVE"),
                                Map.of("modelName", "test-model-b", "defaultModel", false, "status", "ACTIVE")))),
                String.class));

        long id = created.get("id").asLong();
        try {
            assertThat(created.get("providerId").asText()).isEqualTo(code);
            assertThat(created.get("builtin").asBoolean()).isFalse();
            assertThat(created.get("defaultProvider").asBoolean()).isFalse();
            assertThat(created.get("chatModel").asText()).isEqualTo("test-model-a");
            assertThat(created.get("models")).hasSize(2);

            // A duplicate provider code is refused.
            ResponseEntity<String> duplicate = restTemplate.postForEntity(
                    "/api/model-providers",
                    jsonRequest(Map.of(
                            "providerId", code,
                            "name", "重复编码",
                            "protocol", "openai-compatible",
                            "baseUrl", "https://example.invalid/api")),
                    String.class);
            assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(duplicate.getBody()).contains("MODEL_PROVIDER_CODE_EXISTS");

            // Protocols that are not implemented yet are refused with an explicit message.
            ResponseEntity<String> unsupported = restTemplate.postForEntity(
                    "/api/model-providers",
                    jsonRequest(Map.of(
                            "providerId", "test-anthropic-" + UUID.randomUUID().toString().substring(0, 8),
                            "name", "未实现协议",
                            "protocol", "anthropic",
                            "baseUrl", "https://api.anthropic.com")),
                    String.class);
            assertThat(unsupported.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(unsupported.getBody()).contains("MODEL_PROVIDER_PROTOCOL_UNSUPPORTED");

            // Updating with a stale version is refused.
            ResponseEntity<String> staleUpdate = restTemplate.exchange(
                    "/api/model-providers/" + id,
                    HttpMethod.PUT,
                    jsonRequest(Map.of(
                            "name", "过期版本",
                            "protocol", "openai-compatible",
                            "baseUrl", "https://example.invalid/api",
                            "version", 99)),
                    String.class);
            assertThat(staleUpdate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(staleUpdate.getBody()).contains("MODEL_PROVIDER_VERSION_CONFLICT");

            // Updating with the correct version renames the provider and drops the unlisted model.
            JsonNode updated = data(restTemplate.exchange(
                    "/api/model-providers/" + id,
                    HttpMethod.PUT,
                    jsonRequest(Map.of(
                            "name", "临时测试供应商-改名",
                            "protocol", "openai-compatible",
                            "baseUrl", "https://example.invalid/api/",
                            "status", "ACTIVE",
                            "version", created.get("version").asInt(),
                            "reason", "集成测试修改",
                            "models", List.of(
                                    Map.of("modelName", "test-model-a", "defaultModel", true, "status", "ACTIVE")))),
                    String.class));
            assertThat(updated.get("name").asText()).isEqualTo("临时测试供应商-改名");
            assertThat(updated.get("models")).hasSize(1);
            assertThat(updated.get("version").asInt()).isEqualTo(created.get("version").asInt() + 1);

            // Toggling the status disables the provider, and toggling again restores it.
            JsonNode disabled = data(restTemplate.postForEntity(
                    "/api/model-providers/" + id + "/status", jsonRequest(Map.of()), String.class));
            assertThat(disabled.get("enabled").asBoolean()).isFalse();
            JsonNode reenabled = data(restTemplate.postForEntity(
                    "/api/model-providers/" + id + "/status", jsonRequest(Map.of()), String.class));
            assertThat(reenabled.get("enabled").asBoolean()).isTrue();

            // The default provider can be switched and switched back, so shared seed data stays intact.
            long builtinDefaultId = findProvider(
                    data(restTemplate.getForEntity("/api/model-providers", String.class)), "dashscope").get("id").asLong();
            JsonNode promoted = data(restTemplate.postForEntity(
                    "/api/model-providers/" + id + "/set-default", jsonRequest(Map.of()), String.class));
            assertThat(promoted.get("defaultProvider").asBoolean()).isTrue();
            JsonNode restored = data(restTemplate.postForEntity(
                    "/api/model-providers/" + builtinDefaultId + "/set-default", jsonRequest(Map.of()), String.class));
            assertThat(restored.get("defaultProvider").asBoolean()).isTrue();

            // A default provider cannot be deleted, but a plain one can.
            restTemplate.postForEntity("/api/model-providers/" + id + "/set-default", jsonRequest(Map.of()),
                    String.class);
            ResponseEntity<String> defaultDelete = restTemplate.exchange(
                    "/api/model-providers/" + id,
                    HttpMethod.DELETE,
                    jsonRequest(Map.of("reason", "尝试删除默认供应商", "version", 0)),
                    String.class);
            assertThat(defaultDelete.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(defaultDelete.getBody()).contains("MODEL_PROVIDER_DEFAULT_DELETE_REJECTED");
            restTemplate.postForEntity("/api/model-providers/" + builtinDefaultId + "/set-default",
                    jsonRequest(Map.of()), String.class);

            JsonNode current = data(restTemplate.getForEntity("/api/model-providers/" + id, String.class));
            ResponseEntity<String> delete = restTemplate.exchange(
                    "/api/model-providers/" + id,
                    HttpMethod.DELETE,
                    jsonRequest(Map.of("reason", "集成测试清理", "version", current.get("version").asInt())),
                    String.class);
            assertThat(delete.getStatusCode().is2xxSuccessful()).isTrue();

            ResponseEntity<String> missing = restTemplate.getForEntity("/api/model-providers/" + id, String.class);
            assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(missing.getBody()).contains("MODEL_PROVIDER_NOT_FOUND");

            // Provider mutations are audited.
            String auditBody = restTemplate.getForEntity("/api/audit-logs?current=1&size=100", String.class).getBody();
            assertThat(auditBody).contains("CREATE_MODEL_PROVIDER");
            assertThat(auditBody).contains("UPDATE_MODEL_PROVIDER");
            assertThat(auditBody).contains("UPDATE_MODEL_PROVIDER_STATUS");
            assertThat(auditBody).contains("SET_DEFAULT_MODEL_PROVIDER");
            assertThat(auditBody).contains("DELETE_MODEL_PROVIDER");
            assertThat(auditBody).contains("集成测试清理");
        } finally {
            deleteQuietly(id);
        }
    }

    @Test
    void builtinAndDefaultProvidersAreProtectedFromDeletion() throws Exception {
        JsonNode dashscope = findProvider(
                data(restTemplate.getForEntity("/api/model-providers", String.class)), "dashscope");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/model-providers/" + dashscope.get("id").asLong(),
                HttpMethod.DELETE,
                jsonRequest(Map.of(
                        "reason", "尝试删除内置供应商",
                        "version", dashscope.get("version").asInt())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("MODEL_PROVIDER_BUILTIN_PROTECTED");

        // The seeded default provider still resolves, so agent requests without a provider keep working.
        assertThat(modelProviderService.resolveProvider(null).providerId()).isEqualTo("dashscope");
    }

    @Test
    void apiKeyIsEncryptedAtRestAndNeverReturned() throws Exception {
        String code = "test-key-" + UUID.randomUUID().toString().substring(0, 8);
        String plainKey = "sk-SECRET-" + UUID.randomUUID().toString().replace("-", "");

        ResponseEntity<String> createResponse = restTemplate.postForEntity(
                "/api/model-providers",
                jsonRequest(Map.of(
                        "providerId", code,
                        "name", "密钥加密测试",
                        "protocol", "openai-compatible",
                        "baseUrl", "https://example.invalid/api",
                        "apiKey", plainKey,
                        "models", List.of(Map.of("modelName", "key-test-model", "defaultModel", true)))),
                String.class);
        assertThat(createResponse.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode created = data(createResponse);
        long id = created.get("id").asLong();

        try {
            // The response only carries a masked hint, never the key itself.
            assertThat(createResponse.getBody()).doesNotContain(plainKey);
            assertThat(created.get("apiKeyConfigured").asBoolean()).isTrue();
            assertThat(created.get("apiKeyHint").asText())
                    .isEqualTo("****" + plainKey.substring(plainKey.length() - 4));

            // The stored column holds a versioned ciphertext, not the plaintext key.
            ModelProviderEntity stored = modelProviderMapper.selectOne(
                    new LambdaQueryWrapper<ModelProviderEntity>().eq(ModelProviderEntity::getId, id));
            assertThat(stored.getApiKeyCipher()).startsWith("v1:");
            assertThat(stored.getApiKeyCipher()).doesNotContain(plainKey);
            assertThat(providerSecretCipher.decrypt(stored.getApiKeyCipher())).isEqualTo(plainKey);

            // Reads never expose the key either.
            String listBody = restTemplate.getForEntity("/api/model-providers", String.class).getBody();
            assertThat(listBody).doesNotContain(plainKey);
            String detailBody = restTemplate.getForEntity("/api/model-providers/" + id, String.class).getBody();
            assertThat(detailBody).doesNotContain(plainKey);

            // The audit snapshot of the create call must not carry the key.
            String auditBody = restTemplate.getForEntity("/api/audit-logs?current=1&size=100", String.class).getBody();
            assertThat(auditBody).contains("CREATE_MODEL_PROVIDER");
            assertThat(auditBody).doesNotContain(plainKey);

            // A blank key on update keeps the stored one, and clearApiKey removes it.
            JsonNode kept = data(restTemplate.exchange(
                    "/api/model-providers/" + id,
                    HttpMethod.PUT,
                    jsonRequest(Map.of(
                            "name", "密钥加密测试",
                            "protocol", "openai-compatible",
                            "baseUrl", "https://example.invalid/api",
                            "version", created.get("version").asInt())),
                    String.class));
            assertThat(kept.get("apiKeyConfigured").asBoolean()).isTrue();
            assertThat(kept.get("apiKeyHint").asText()).isEqualTo(created.get("apiKeyHint").asText());

            JsonNode cleared = data(restTemplate.exchange(
                    "/api/model-providers/" + id,
                    HttpMethod.PUT,
                    jsonRequest(Map.of(
                            "name", "密钥加密测试",
                            "protocol", "openai-compatible",
                            "baseUrl", "https://example.invalid/api",
                            "clearApiKey", true,
                            "version", kept.get("version").asInt())),
                    String.class));
            assertThat(cleared.get("apiKeyConfigured").asBoolean()).isFalse();
            assertThat(cleared.get("apiKeyHint").isNull()).isTrue();
        } finally {
            deleteQuietly(id);
        }
    }

    @Test
    void baseUrlIsNormalizedToTheServiceRoot() throws Exception {
        String code = "test-url-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = data(restTemplate.postForEntity(
                "/api/model-providers",
                jsonRequest(Map.of(
                        "providerId", code,
                        "name", "地址规范化测试",
                        "protocol", "openai-compatible",
                        "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1/",
                        "models", List.of(Map.of("modelName", "url-model", "defaultModel", true)))),
                String.class));
        long id = created.get("id").asLong();

        try {
            // The version segment belongs to the request path, so it must not remain in the base URL;
            // otherwise the chat call would go to /compatible-mode/v1/v1/chat/completions.
            assertThat(created.get("baseUrl").asText()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode");
            assertThat(created.get("chatCompletionsPath").asText()).isEqualTo("/v1/chat/completions");
            assertThat(created.get("modelsPath").asText()).isEqualTo("/v1/models");

            // With an explicit chat path the caller owns the versioning, so the base URL is kept as given.
            JsonNode updated = data(restTemplate.exchange(
                    "/api/model-providers/" + id,
                    HttpMethod.PUT,
                    jsonRequest(Map.of(
                            "name", "地址规范化测试",
                            "protocol", "openai-compatible",
                            "baseUrl", "https://example.invalid/gateway/v1",
                            "chatCompletionsPath", "/chat/completions",
                            "modelsPath", "/models",
                            "version", created.get("version").asInt())),
                    String.class));
            assertThat(updated.get("baseUrl").asText()).isEqualTo("https://example.invalid/gateway/v1");
            assertThat(updated.get("chatCompletionsPath").asText()).isEqualTo("/chat/completions");
            // Omitting the model list on update must leave the existing models untouched.
            assertThat(updated.get("models")).hasSize(1);
        } finally {
            deleteQuietly(id);
        }
    }

    @Test
    void connectivityProbePerformsARealRequestAndPersistsTheOutcome() throws Exception {
        String code = "test-probe-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = data(restTemplate.postForEntity(
                "/api/model-providers",
                jsonRequest(Map.of(
                        "providerId", code,
                        "name", "探活测试供应商",
                        "protocol", "openai-compatible",
                        "baseUrl", UNREACHABLE_BASE_URL,
                        "timeoutSeconds", 3,
                        "models", List.of(Map.of("modelName", "probe-model", "defaultModel", true)))),
                String.class));
        long id = created.get("id").asLong();

        try {
            assertThat(created.get("lastTest").isNull()).isTrue();

            JsonNode result = data(restTemplate.postForEntity(
                    "/api/model-providers/test",
                    jsonRequest(Map.of("providerId", code, "probeChat", false)),
                    String.class));

            assertThat(result.get("success").asBoolean()).isFalse();
            assertThat(result.get("status").asText()).isEqualTo("UNREACHABLE");
            assertThat(result.get("message").asText()).contains("连接被拒绝");
            assertThat(result.get("providerId").asText()).isEqualTo(code);

            // The outcome is stored on the provider row, so the page shows it after a reload.
            JsonNode reloaded = data(restTemplate.getForEntity("/api/model-providers/" + id, String.class));
            assertThat(reloaded.get("lastTest").get("status").asText()).isEqualTo("UNREACHABLE");
            assertThat(reloaded.get("lastTest").get("testedAt").isNull()).isFalse();

            String auditBody = restTemplate.getForEntity("/api/audit-logs?current=1&size=100", String.class).getBody();
            assertThat(auditBody).contains("TEST_MODEL_PROVIDER");
        } finally {
            deleteQuietly(id);
        }
    }

    @Test
    void chatModelIsBuiltFromStoredProviderConfiguration() throws Exception {
        String code = "test-factory-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = data(restTemplate.postForEntity(
                "/api/model-providers",
                jsonRequest(Map.of(
                        "providerId", code,
                        "name", "ChatModel 构建测试",
                        "protocol", "openai-compatible",
                        "baseUrl", "https://example.invalid/",
                        "timeoutSeconds", 7,
                        "temperature", 0.3,
                        "models", List.of(Map.of("modelName", "factory-model", "defaultModel", true)))),
                String.class));
        long id = created.get("id").asLong();

        try {
            ModelEndpointTO endpoint = modelProviderService.resolveEndpoint(code, null);
            assertThat(endpoint.baseUrl()).isEqualTo("https://example.invalid");
            assertThat(endpoint.chatCompletionsPath()).isEqualTo("/v1/chat/completions");
            assertThat(endpoint.modelsPath()).isEqualTo("/v1/models");
            assertThat(endpoint.modelName()).isEqualTo("factory-model");
            assertThat(endpoint.timeoutSeconds()).isEqualTo(7);
            // The endpoint transfer object masks the key in its textual form.
            assertThat(endpoint.toString()).doesNotContain("apiKey=");

            assertThat(chatModelFactory.create(endpoint)).isNotNull();
            assertThat(chatModelFactory.create(endpoint).getDefaultOptions().getModel()).isEqualTo("factory-model");
        } finally {
            deleteQuietly(id);
        }
    }

    // ---------- helpers ----------

    private void deleteQuietly(long id) {
        try {
            JsonNode current = data(restTemplate.getForEntity("/api/model-providers/" + id, String.class));
            if (current.get("defaultProvider").asBoolean()) {
                long builtinDefaultId = findProvider(
                        data(restTemplate.getForEntity("/api/model-providers", String.class)), "dashscope").get("id").asLong();
                restTemplate.postForEntity("/api/model-providers/" + builtinDefaultId + "/set-default",
                        jsonRequest(Map.of()), String.class);
                current = data(restTemplate.getForEntity("/api/model-providers/" + id, String.class));
            }
            restTemplate.exchange(
                    "/api/model-providers/" + id,
                    HttpMethod.DELETE,
                    jsonRequest(Map.of("reason", "集成测试清理", "version", current.get("version").asInt())),
                    String.class);
        } catch (Exception ignored) {
            // Cleanup must not mask the original assertion failure.
        }
    }

    private JsonNode findProvider(JsonNode providers, String providerCode) {
        for (JsonNode provider : providers) {
            if (providerCode.equals(provider.get("providerId").asText())) {
                return provider;
            }
        }
        return null;
    }

    private JsonNode findProtocol(JsonNode protocols, String code) {
        for (JsonNode protocol : protocols) {
            if (code.equals(protocol.get("code").asText())) {
                return protocol;
            }
        }
        throw new IllegalStateException("Protocol not found: " + code);
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
