package com.rcdis.agent.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.agent.AgentToolContext;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.ProjectPageRequest;
import com.rcdis.agent.service.ResearchProjectService;
import com.rcdis.agent.vo.ProjectVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-only budget tools. Delegates entirely to domain services; never touches mappers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectBudgetTools {

    private static final String TOOL_QUERY_BUDGET = "query_project_budget";
    private static final String TOOL_LIST_PROJECTS = "list_projects";
    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;

    private final ResearchProjectService researchProjectService;
    private final ObjectMapper objectMapper;

    @Tool(name = TOOL_QUERY_BUDGET,
            description = "查询某个科研项目的预算总览：项目基本信息、总预算、已用额、冻结额、可用额（项目级单一预算池）。"
                    + "只读，不修改任何数据。参数 projectCode 为项目编号，例如 P-2026-001。")
    public String queryProjectBudget(
            @ToolParam(description = "项目编号，例如 P-2026-001") String projectCode,
            ToolContext toolContext) {
        AgentToolContext ctx = AgentToolContext.from(toolContext);
        ToolReporting.start(ctx, TOOL_QUERY_BUDGET, Map.of("projectCode", String.valueOf(projectCode)));
        try {
            if (!StringUtils.hasText(projectCode)) {
                ToolReporting.failure(ctx, TOOL_QUERY_BUDGET, "缺少项目编号");
                return json(Map.of("ok", false, "error", "请提供项目编号 projectCode"));
            }
            ProjectVO project = resolveProjectByCode(projectCode.trim());
            if (project == null) {
                ToolReporting.failure(ctx, TOOL_QUERY_BUDGET, "项目不存在");
                return json(Map.of("ok", false, "error", "未找到项目编号 " + projectCode + "，请核对后重试"));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("projectCode", project.projectCode());
            result.put("projectName", project.projectName());
            result.put("status", project.status());
            result.put("totalBudget", project.totalBudget());
            result.put("usedAmount", project.usedAmount());
            result.put("frozenAmount", project.frozenAmount());
            result.put("availableAmount", project.availableAmount());
            ToolReporting.success(ctx, TOOL_QUERY_BUDGET);
            return json(result);
        } catch (RuntimeException exception) {
            ToolReporting.failure(ctx, TOOL_QUERY_BUDGET, exception.getMessage());
            return json(Map.of("ok", false, "error", "查询预算失败：" + exception.getMessage()));
        }
    }

    @Tool(name = TOOL_LIST_PROJECTS,
            description = "列出科研项目（只读）。当用户不知道有哪些项目、报销该挂在哪个项目下，或问'现在有哪些科研项目'时必须先调用本工具。"
                    + "返回每个项目的编号/名称/负责人/状态/总预算/剩余预算，便于用户选择。参数 keyword 可选（按编号或名称模糊匹配）；"
                    + "status 可选（ACTIVE/SUSPENDED/CLOSED，不传默认只列 ACTIVE 的进行中项目）；limit 为条数上限（默认 50，最大 200）。")
    public String listProjects(
            @ToolParam(description = "按项目编号或名称模糊匹配，可留空", required = false) String keyword,
            @ToolParam(description = "项目状态 ACTIVE/SUSPENDED/CLOSED，不传默认只列 ACTIVE", required = false) String status,
            @ToolParam(description = "返回条数上限，默认 50，最大 200", required = false) Integer limit,
            ToolContext toolContext) {
        AgentToolContext ctx = AgentToolContext.from(toolContext);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("keyword", String.valueOf(keyword));
        args.put("status", String.valueOf(status));
        ToolReporting.start(ctx, TOOL_LIST_PROJECTS, args);
        try {
            long size = clampLimit(limit);
            String effectiveStatus = StringUtils.hasText(status)
                    ? status.trim().toUpperCase() : "ACTIVE";
            PageResponse<ProjectVO> page = researchProjectService.pageProjects(
                    new ProjectPageRequest(1, size, StringUtils.hasText(keyword) ? keyword.trim() : null, effectiveStatus));

            List<Map<String, Object>> projects = new ArrayList<>();
            for (ProjectVO vo : page.records()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("projectCode", vo.projectCode());
                item.put("projectName", vo.projectName());
                item.put("principalInvestigator", vo.principalInvestigator());
                item.put("status", vo.status());
                item.put("totalBudget", vo.totalBudget());
                item.put("availableAmount", vo.availableAmount());
                projects.add(item);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("statusFilter", effectiveStatus);
            result.put("returned", projects.size());
            result.put("total", page.total());
            result.put("projects", projects);
            if (projects.isEmpty()) {
                result.put("hint", "没有匹配的进行中项目；可去掉 keyword 或改 status 再查");
            }
            ToolReporting.success(ctx, TOOL_LIST_PROJECTS);
            return json(result);
        } catch (RuntimeException exception) {
            ToolReporting.failure(ctx, TOOL_LIST_PROJECTS, exception.getMessage());
            return json(Map.of("ok", false, "error", "查询项目列表失败：" + exception.getMessage()));
        }
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    /** Resolves a project by exact code via the keyword search plus an exact-match filter. */
    private ProjectVO resolveProjectByCode(String code) {
        PageResponse<ProjectVO> page = researchProjectService.pageProjects(
                new ProjectPageRequest(1, 20, code, null));
        return page.records().stream()
                .filter(vo -> code.equalsIgnoreCase(vo.projectCode()))
                .findFirst()
                .orElse(null);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{\"ok\":false,\"error\":\"序列化工具结果失败\"}";
        }
    }
}
