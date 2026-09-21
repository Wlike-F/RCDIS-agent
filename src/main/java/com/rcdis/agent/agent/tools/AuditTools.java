package com.rcdis.agent.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.agent.AgentToolContext;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.service.AuditLogService;
import com.rcdis.agent.vo.AuditLogVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-only audit trail tool. Delegates entirely to {@link AuditLogService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditTools {

    private static final String TOOL_LIST_AUDIT_LOGS = "list_audit_logs";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @Tool(name = TOOL_LIST_AUDIT_LOGS,
            description = "查询系统审计留痕（只读），按时间倒序。返回操作人、动作、目标类型/目标 id、原因、来源、"
                    + "关联会话 id、时间。用于回答'谁在什么时候改了什么'。参数 limit 为条数上限（默认 20，最大 50）。")
    public String listAuditLogs(
            @ToolParam(description = "返回条数上限，默认 20，最大 50", required = false) Integer limit,
            ToolContext toolContext) {
        AgentToolContext ctx = AgentToolContext.from(toolContext);
        ToolReporting.start(ctx, TOOL_LIST_AUDIT_LOGS, Map.of("limit", String.valueOf(limit)));
        try {
            int size = clampLimit(limit);
            PageResponse<AuditLogVO> page = auditLogService.list(1, size);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("total", page.total());
            result.put("returned", page.records().size());
            result.put("auditLogs", page.records());
            ToolReporting.success(ctx, TOOL_LIST_AUDIT_LOGS);
            return json(result);
        } catch (RuntimeException exception) {
            ToolReporting.failure(ctx, TOOL_LIST_AUDIT_LOGS, exception.getMessage());
            return json(Map.of("ok", false, "error", "查询审计日志失败：" + exception.getMessage()));
        }
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{\"ok\":false,\"error\":\"序列化工具结果失败\"}";
        }
    }
}
