package com.rcdis.agent.service.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.agent.AgentToolRegistry;
import com.rcdis.agent.service.AgentToolCatalogService;
import com.rcdis.agent.vo.AgentToolParamVO;
import com.rcdis.agent.vo.AgentToolVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reflects the registered {@code @Tool} beans into a developer-readable catalogue.
 *
 * <p>Metadata (name / description / JSON schema) comes straight from Spring AI's
 * {@link MethodToolCallbackProvider}, so the console can never drift from what the ChatClient
 * actually exposes. The read/write category is a small explicit map because it is a product policy
 * (write tools are confirmation-gated), not something derivable from reflection.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentToolCatalogServiceImpl implements AgentToolCatalogService {

    private static final String CATEGORY_READ = "READ";
    private static final String CATEGORY_WRITE = "WRITE";
    private static final String STATUS_ENABLED = "ENABLED";

    /**
     * Product policy: which tools mutate data and therefore require confirmation. Read tools execute
     * directly; write tools only create a pending proposal that a human must approve.
     */
    private static final Map<String, String> TOOL_CATEGORY = Map.ofEntries(
            Map.entry("query_project_budget", CATEGORY_READ),
            Map.entry("list_projects", CATEGORY_READ),
            Map.entry("list_reimbursements", CATEGORY_READ),
            Map.entry("get_reimbursement", CATEGORY_READ),
            Map.entry("list_pending_approvals", CATEGORY_READ),
            Map.entry("summarize_expenses", CATEGORY_READ),
            Map.entry("list_expense_items", CATEGORY_READ),
            Map.entry("check_reimbursement_materials", CATEGORY_READ),
            Map.entry("generate_reimbursement_summary", CATEGORY_READ),
            Map.entry("list_audit_logs", CATEGORY_READ),
            Map.entry("get_current_datetime", CATEGORY_READ),
            Map.entry("parse_date", CATEGORY_READ),
            Map.entry("date_range", CATEGORY_READ),
            Map.entry("sum_amounts", CATEGORY_READ),
            Map.entry("budget_remaining", CATEGORY_READ),
            Map.entry("recall_history", CATEGORY_READ),
            Map.entry("get_receipt_ocr", CATEGORY_READ),
            // Registers a receipt reference for a user-owned chat attachment: creates file metadata
            // only, no financial state change, therefore direct execution by design.
            Map.entry("register_attachment_receipt", CATEGORY_READ),
            Map.entry("submit_reimbursement", CATEGORY_WRITE),
            Map.entry("create_reimbursement", CATEGORY_WRITE),
            Map.entry("update_reimbursement", CATEGORY_WRITE),
            Map.entry("void_reimbursement", CATEGORY_WRITE),
            Map.entry("plan_reimbursement_submissions", CATEGORY_WRITE),
            Map.entry("retry_reimbursement_plan", CATEGORY_WRITE));

    private final AgentToolRegistry agentToolRegistry;
    private final ObjectMapper objectMapper;

    @Override
    public List<AgentToolVO> listTools() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(agentToolRegistry.toolBeans().toArray())
                .build()
                .getToolCallbacks();

        List<AgentToolVO> tools = new ArrayList<>(callbacks.length);
        for (ToolCallback callback : callbacks) {
            ToolDefinition definition = callback.getToolDefinition();
            String category = TOOL_CATEGORY.get(definition.name());
            if (category == null) {
                throw new IllegalStateException(
                        "Registered Agent tool has no explicit risk category: " + definition.name());
            }
            tools.add(new AgentToolVO(
                    definition.name(),
                    definition.description(),
                    category,
                    CATEGORY_WRITE.equals(category),
                    STATUS_ENABLED,
                    parseParams(definition.inputSchema())));
        }
        tools.sort((a, b) -> a.name().compareTo(b.name()));
        return tools;
    }

    @Override
    public String promptCatalogue() {
        StringBuilder catalogue = new StringBuilder("\n\n# 运行时工具目录（唯一事实源）\n");
        catalogue.append("以下目录由实际注册的 Spring AI 工具自动生成；未出现在目录中的工具不存在，不得声称可以调用。\n");
        for (AgentToolVO tool : listTools()) {
            catalogue.append("- ").append(tool.name())
                    .append(" [").append(tool.category()).append(']')
                    .append(tool.requiresConfirmation() ? " [需人工确认]" : "")
                    .append("：").append(tool.description()).append('\n');
        }
        return catalogue.toString();
    }

    /**
     * Turns the tool's JSON schema ({@code {"properties":{...},"required":[...]}}) into a flat
     * parameter list. Malformed or absent schemas degrade to an empty list rather than failing the
     * whole catalogue.
     */
    private List<AgentToolParamVO> parseParams(String inputSchema) {
        List<AgentToolParamVO> params = new ArrayList<>();
        if (inputSchema == null || inputSchema.isBlank()) {
            return params;
        }
        try {
            JsonNode root = objectMapper.readTree(inputSchema);
            JsonNode properties = root.path("properties");
            List<String> required = new ArrayList<>();
            for (JsonNode req : root.path("required")) {
                required.add(req.asText());
            }
            Map<String, JsonNode> ordered = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                ordered.put(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, JsonNode> entry : ordered.entrySet()) {
                JsonNode node = entry.getValue();
                params.add(new AgentToolParamVO(
                        entry.getKey(),
                        node.path("type").asText("string"),
                        required.contains(entry.getKey()),
                        node.path("description").asText("")));
            }
        } catch (Exception exception) {
            log.atWarn()
                    .setCause(exception)
                    .log("Failed to parse tool input schema; returning empty parameter list");
        }
        return params;
    }
}
