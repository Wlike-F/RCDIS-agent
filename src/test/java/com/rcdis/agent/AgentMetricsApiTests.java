package com.rcdis.agent;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
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
import com.rcdis.agent.agent.AgentMetrics;
import com.rcdis.agent.agent.AgentToolContext;
import com.rcdis.agent.agent.RecordingToolCallback;
import com.rcdis.agent.agent.TurnTraceCollector;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.service.AgentToolAuthorizationService;
import com.rcdis.agent.service.ChatHistoryService;
import com.rcdis.agent.service.JwtTokenService;
import com.rcdis.agent.support.TestAuth;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Integration coverage for the administrator-facing Agent metrics snapshot. */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "rcdis.feishu.enabled=false",
                "rcdis.feishu.client-type=noop",
                "rcdis.agent.input-cost-per-thousand-tokens=0.5",
                "rcdis.agent.output-cost-per-thousand-tokens=1.5"
        })
class AgentMetricsApiTests {

    private static final String SUMMARY_URL = "/api/admin/agent-metrics/summary";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private AgentMetrics agentMetrics;

    @Autowired
    private AgentToolAuthorizationService agentToolAuthorizationService;

    @Autowired
    private ChatHistoryService chatHistoryService;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void useExplicitPerRequestTokens() {
        restTemplate.getRestTemplate().getInterceptors().clear();
    }

    @Test
    void summaryRequiresAdministratorRole() throws Exception {
        ResponseEntity<String> researcher = get(TestAuth.researcherToken(jwtTokenService));
        ResponseEntity<String> administrator = get(TestAuth.adminToken(jwtTokenService));

        assertThat(researcher.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(administrator.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(administrator).get("generatedAt").asText()).isNotBlank();
    }

    @Test
    void summaryAggregatesRecordedAgentMetricsWithoutDoubleCountingTokens() throws Exception {
        String adminToken = TestAuth.adminToken(jwtTokenService);
        JsonNode before = data(get(adminToken));
        String toolName = "metrics_test_" + UUID.randomUUID().toString().substring(0, 8);

        agentMetrics.recordTool(toolName, true, 100L);
        agentMetrics.recordTool(toolName, false, 300L);
        agentMetrics.recordMemoryInjection(3);
        agentMetrics.recordMemoryInjection(0);
        agentMetrics.recordConfirmation("rejected");
        agentMetrics.recordTaskStep("FAILED");
        agentMetrics.recordRecovery("recovered");
        agentMetrics.recordSecurityBlock("forbidden");

        TurnTraceCollector collector = new TurnTraceCollector();
        collector.setUsage(11, 7, 18);
        collector.markFirstToken();
        collector.finish();
        agentMetrics.recordTurn("metrics-test", "metrics-test-model", "DONE", collector);

        JsonNode after = data(get(adminToken));
        JsonNode tool = findTool(after.get("tools").get("byTool"), toolName);

        assertThat(tool.get("totalCalls").asLong()).isEqualTo(2L);
        assertThat(tool.get("successfulCalls").asLong()).isEqualTo(1L);
        assertThat(tool.get("failedCalls").asLong()).isEqualTo(1L);
        assertThat(tool.get("successRate").asDouble()).isEqualTo(0.5);
        assertThat(tool.get("averageDurationMs").asDouble()).isCloseTo(200.0, within(0.01));

        assertDelta(before, after, "/tokens/promptTokens", 11L);
        assertDelta(before, after, "/tokens/completionTokens", 7L);
        assertDelta(before, after, "/tokens/totalTokens", 18L);
        double costDelta = after.at("/tokens/estimatedCost").asDouble()
                - before.at("/tokens/estimatedCost").asDouble();
        assertThat(costDelta).isCloseTo(0.016, within(0.000001));
        assertThat(after.at("/tokens/costConfigured").asBoolean()).isTrue();

        assertDelta(before, after, "/memory/hits", 1L);
        assertDelta(before, after, "/memory/misses", 1L);
        assertDelta(before, after, "/memory/itemsInjected", 3L);
        assertThat(after.at("/memory/hitRate").asDouble()).isBetween(0.0, 1.0);
        assertTaggedDelta(before, after, "turns", "DONE", 1L);
        assertTaggedDelta(before, after, "confirmations", "rejected", 1L);
        assertTaggedDelta(before, after, "taskSteps", "FAILED", 1L);
        assertTaggedDelta(before, after, "recoveries", "recovered", 1L);
        assertTaggedDelta(before, after, "securityBlocks", "forbidden", 1L);
        assertDelta(before, after, "/firstTokenLatency/sampleCount", 1L);
        assertDelta(before, after, "/turnLatency/sampleCount", 1L);
    }

    @Test
    void recordingToolCallbackUpdatesCallAndDurationMetrics() throws Exception {
        String toolName = "metrics_callback_" + UUID.randomUUID().toString().substring(0, 8);
        ToolDefinition definition = ToolDefinition.builder()
                .name(toolName)
                .description("Metrics integration test tool")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "{\"ok\":true}";
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return call(toolInput);
            }
        };
        RecordingToolCallback callback = new RecordingToolCallback(
                delegate,
                chatHistoryService,
                agentToolAuthorizationService,
                agentMetrics);
        CurrentUserTO user = CurrentUserTO.of(
                "metrics-user-id",
                "metrics-user",
                "test",
                null,
                Set.of("RESEARCHER"));
        AgentToolContext agentContext = new AgentToolContext(
                null,
                "metrics-conversation-" + UUID.randomUUID(),
                user,
                null,
                null);
        ToolContext toolContext = new ToolContext(AgentToolContext.asToolContextMap(agentContext));
        long durationSamplesBefore = toolDurationSamples(toolName);

        String result = callback.call("{}", toolContext);

        JsonNode after = data(get(TestAuth.adminToken(jwtTokenService)));
        JsonNode tool = findTool(after.get("tools").get("byTool"), toolName);
        assertThat(result).isEqualTo("{\"ok\":true}");
        assertThat(tool.get("totalCalls").asLong()).isEqualTo(1L);
        assertThat(tool.get("successfulCalls").asLong()).isEqualTo(1L);
        assertThat(tool.get("failedCalls").asLong()).isZero();
        assertThat(toolDurationSamples(toolName) - durationSamplesBefore).isEqualTo(1L);
    }

    private ResponseEntity<String> get(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(SUMMARY_URL, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).get("data");
    }

    private JsonNode findTool(JsonNode tools, String toolName) {
        for (JsonNode tool : tools) {
            if (toolName.equals(tool.get("tool").asText())) {
                return tool;
            }
        }
        throw new AssertionError("Tool metrics were not found for " + toolName);
    }

    private long toolDurationSamples(String toolName) {
        Timer timer = meterRegistry.find("rcdis_agent_tool_duration")
                .tag("tool", toolName)
                .tag("outcome", "success")
                .timer();
        return timer == null ? 0L : timer.count();
    }

    private void assertDelta(JsonNode before, JsonNode after, String pointer, long expectedDelta) {
        assertThat(after.at(pointer).asLong() - before.at(pointer).asLong()).isEqualTo(expectedDelta);
    }

    private void assertTaggedDelta(
            JsonNode before,
            JsonNode after,
            String group,
            String value,
            long expectedDelta) {
        long previous = before.path(group).path("values").path(value).asLong(0L);
        long current = after.path(group).path("values").path(value).asLong(0L);
        assertThat(current - previous).isEqualTo(expectedDelta);
    }

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
