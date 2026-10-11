package com.rcdis.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.agent.AgentMetrics;
import com.rcdis.agent.agent.AgentToolContext;
import com.rcdis.agent.agent.ChatStreamListener;
import com.rcdis.agent.agent.RecordingToolCallback;
import com.rcdis.agent.agent.TurnTraceCollector;
import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.service.AgentToolAuthorizationService;
import com.rcdis.agent.service.ChatHistoryService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Unit coverage for the runtime circuit breaker installed inside {@link RecordingToolCallback}.
 *
 * <p>Every scenario drives a fresh {@link TurnTraceCollector} through the callback so tier
 * escalation is exercised exactly the way the live pipeline uses it: same tool, same JSON input,
 * delegate returning {@code ok:false}. The soft hint tier keeps the delegate result but appends a
 * key; the hard halt tier replaces it with a {@code halt:true} block; the freeze tier short
 * circuits before the delegate is invoked.</p>
 */
class RecordingToolCallbackHaltTests {

    private ChatHistoryService chatHistoryService;
    private AgentToolAuthorizationService authorizationService;
    private ObjectMapper objectMapper;
    private AgentProperties properties;
    private AgentMetrics metrics;

    @BeforeEach
    void setUp() {
        chatHistoryService = mock(ChatHistoryService.class);
        authorizationService = mock(AgentToolAuthorizationService.class);
        objectMapper = new ObjectMapper();
        properties = new AgentProperties();
        properties.getGuard().setEnabled(true);
        properties.getGuard().setHardHaltThreshold(2);
        properties.getGuard().setFreezeThreshold(3);
        properties.getGuard().setTurnToolCallCeiling(12);
        metrics = new AgentMetrics(new SimpleMeterRegistry(), properties);
    }

    @AfterEach
    void tearDown() {
        // Nothing to release; hook left for future ThreadLocal cleanup additions.
    }

    @Test
    void firstFailureAppendsSoftHintWithoutReplacingDelegateResult() {
        ToolCallback delegate = failingToolCallback("query_project_budget",
                "{\"ok\":false,\"error\":\"未找到项目\"}");
        TurnTraceCollector collector = new TurnTraceCollector();
        RecordingToolCallback callback = newRecording(delegate);

        String result = callback.call("{\"projectCode\":\"P-MISSING\"}", toolContext(collector));

        assertThat(result).contains("\"ok\":false").contains("haltHint");
        assertThat(collector.failureCount("query_project_budget")).isEqualTo(1);
        // No needs-input yet: the first failure stays at tier SOFT_HINT.
        assertThat(collector.drainNeedsInput()).isEmpty();
    }

    @Test
    void secondFailureOnSameToolReplacesResultAndQueuesNeedsInput() {
        ToolCallback delegate = failingToolCallback("query_project_budget",
                "{\"ok\":false,\"error\":\"未找到项目\"}");
        TurnTraceCollector collector = new TurnTraceCollector();
        RecordingToolCallback callback = newRecording(delegate);

        callback.call("{\"projectCode\":\"P-MISSING\"}", toolContext(collector));
        String second = callback.call("{\"projectCode\":\"P-MISSING\"}", toolContext(collector));

        assertThat(second).contains("\"halt\":true").contains("HARD_HALT")
                .contains("list_projects");
        List<Map<String, Object>> queued = collector.drainNeedsInput();
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0)).containsEntry("tier", "HARD_HALT")
                .containsEntry("sourceTool", "query_project_budget")
                .containsEntry("missingFields", List.of("projectCode"));
    }

    @Test
    void thirdFailureFreezesToolAndShortCircuitsDelegateOnSubsequentCalls() {
        ToolCallback delegate = failingToolCallback("query_project_budget",
                "{\"ok\":false,\"error\":\"未找到项目\"}");
        TurnTraceCollector collector = new TurnTraceCollector();
        RecordingToolCallback callback = newRecording(delegate);

        for (int attempt = 0; attempt < 3; attempt++) {
            callback.call("{\"projectCode\":\"P-MISSING\"}", toolContext(collector));
        }
        assertThat(collector.isFrozen("query_project_budget")).isTrue();

        // Fourth call must NOT reach the delegate; the callback short-circuits with a frozen JSON.
        String fourth = callback.call("{\"projectCode\":\"P-MISSING\"}", toolContext(collector));
        assertThat(fourth).contains("\"frozen\":true");
        verify(delegate, times(3)).call(anyString(), any(ToolContext.class));
    }

    @Test
    void successfulCallAfterFailureResetsStreak() {
        ToolCallback failing = failingToolCallback("query_project_budget",
                "{\"ok\":false,\"error\":\"未找到项目\"}");
        TurnTraceCollector collector = new TurnTraceCollector();
        RecordingToolCallback callback = newRecording(failing);

        callback.call("{\"projectCode\":\"P-MISSING\"}", toolContext(collector));
        assertThat(collector.failureCount("query_project_budget")).isEqualTo(1);

        // Swap delegate mid-turn to a successful one; the streak should reset so a fresh
        // failure afterwards starts tier counting from 1 again.
        ToolCallback success = failingToolCallback("query_project_budget", "{\"ok\":true,\"balance\":100}");
        RecordingToolCallback successCallback = newRecording(success);
        String okResult = successCallback.call("{\"projectCode\":\"P-FOUND\"}", toolContext(collector));
        assertThat(okResult).doesNotContain("haltHint").doesNotContain("halt\":true");
        assertThat(collector.failureCount("query_project_budget")).isZero();

        // Now fail again on the original callback; tier must be SOFT_HINT, not HARD_HALT.
        String softAgain = callback.call("{\"projectCode\":\"P-AGAIN\"}", toolContext(collector));
        assertThat(softAgain).contains("haltHint").doesNotContain("HARD_HALT");
    }

    @Test
    void guardDisabledPassesDelegateResultThroughUnchanged() {
        properties.getGuard().setEnabled(false);
        ToolCallback delegate = failingToolCallback("query_project_budget",
                "{\"ok\":false,\"error\":\"未找到项目\"}");
        TurnTraceCollector collector = new TurnTraceCollector();
        RecordingToolCallback callback = newRecording(delegate);

        String result = callback.call("{\"projectCode\":\"P-MISSING\"}", toolContext(collector));

        // Result unchanged (no haltHint appended, no halt:true block).
        assertThat(result).isEqualTo("{\"ok\":false,\"error\":\"未找到项目\"}");
    }

    @Test
    void silentEmptyListOnReadToolCountsAsFailureButMetaToolsNeverFreeze() {
        ToolCallback delegate = failingToolCallback("list_projects",
                "{\"ok\":true,\"records\":[]}");
        TurnTraceCollector collector = new TurnTraceCollector();
        RecordingToolCallback callback = newRecording(delegate);

        // First call: SOFT_HINT (haltHint appended to the delegate's own JSON).
        String first = callback.call("{}", toolContext(collector));
        assertThat(first).contains("haltHint");

        // Second call: HARD_HALT (delegate result replaced with the halt:true block).
        String second = callback.call("{}", toolContext(collector));
        assertThat(second).contains("halt\":true").contains("HARD_HALT");

        // Third call reaches FREEZE severity but must downgrade because list_projects is a
        // meta tool the breaker relies on as the escape path; it stays unfrozen.
        callback.call("{}", toolContext(collector));
        assertThat(collector.isFrozen("list_projects")).isFalse();
    }

    @Test
    void exceptionThrownByDelegateConvertsIntoStructuredFailureJson() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("query_project_budget");
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(anyString(), any(ToolContext.class)))
                .thenThrow(new IllegalStateException("db down"));
        TurnTraceCollector collector = new TurnTraceCollector();
        RecordingToolCallback callback = newRecording(delegate);

        String first = callback.call("{\"projectCode\":\"P\"}", toolContext(collector));

        assertThat(first).contains("\"ok\":false").contains("db down");
        assertThat(collector.failureCount("query_project_budget")).isEqualTo(1);
    }

    @Test
    void haltEmitsNeedsInputAtTurnEndThroughCollectorDrain() {
        ToolCallback delegate = failingToolCallback("query_project_budget",
                "{\"ok\":false,\"error\":\"未找到项目\"}");
        TurnTraceCollector collector = new TurnTraceCollector();
        ChatStreamListener listener = mock(ChatStreamListener.class);
        RecordingToolCallback callback = newRecording(delegate);
        AgentToolContext context = new AgentToolContext(
                listener, "conv-1", null, collector, null);
        ToolContext toolContext = new ToolContext(Map.of(
                AgentToolContext.CONTEXT_KEY, context));

        callback.call("{\"projectCode\":\"P-MISSING\"}", toolContext);
        callback.call("{\"projectCode\":\"P-MISSING\"}", toolContext);

        List<Map<String, Object>> queued = collector.drainNeedsInput();
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0)).containsEntry("tier", "HARD_HALT");
        // Listener itself is not invoked by the callback; the pipeline drains and pushes at turn end.
        verify(listener, never()).onNeedsInput(any());
    }

    // ---------- helpers ----------

    private RecordingToolCallback newRecording(ToolCallback delegate) {
        return new RecordingToolCallback(
                delegate, chatHistoryService, authorizationService,
                metrics, properties, objectMapper);
    }

    private ToolCallback failingToolCallback(String name, String resultJson) {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn(resultJson);
        return delegate;
    }

    private ToolContext toolContext(TurnTraceCollector collector) {
        AgentToolContext context = new AgentToolContext(
                null, "conv-test", null, collector, null);
        return new ToolContext(Map.of(AgentToolContext.CONTEXT_KEY, context));
    }
}
