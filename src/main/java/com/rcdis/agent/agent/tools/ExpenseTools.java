package com.rcdis.agent.agent.tools;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.agent.AgentToolContext;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.dto.ExpenseQueryFilter;
import com.rcdis.agent.service.ExpenseAnalysisService;
import com.rcdis.agent.to.ExpenseItemRowTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-only expense tools. Every amount comes from SQL aggregation in
 * {@link ExpenseAnalysisService}; the model only reads numbers back and never computes them.
 *
 * <p>Record scope: researchers only see expenses of their own reimbursement orders, while admins
 * and approvers see the whole lab. The scope is derived from the explicit tool-context user because
 * tool invocations run on reactor threads without a thread-local identity.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpenseTools {

    private static final String TOOL_SUMMARIZE = "summarize_expenses";
    private static final String TOOL_LIST_ITEMS = "list_expense_items";
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 200;

    private final ExpenseAnalysisService expenseAnalysisService;
    private final ObjectMapper objectMapper;

    @Tool(name = TOOL_SUMMARIZE,
            description = "统计支出金额（只读）。所有数字由数据库聚合得出，你不得自行心算或编造金额。支出即报销明细行。"
                    + "可选维度 groupBy：project 按项目 / month 按月份 / applicant 按申请人 / status 按单据状态 / paymentType 按支付方式，不传则只返回总计。"
                    + "可选过滤：projectCode 项目编号、month 月份(YYYY-MM)、dateFrom/dateTo 支出日期区间(YYYY-MM-DD)、"
                    + "status 单据状态（可多个，逗号分隔，如 approved,submitted；默认只统计 approved 已入账支出）。"
                    + "返回 rows（分组值/笔数/金额）、totalCount 总笔数、grandTotal 总金额。"
                    + "典型用法：“今年哪个项目花得最多”→ groupBy=project；“上个月公卡花了多少”→ month + paymentType 过滤或 groupBy=paymentType。")
    public String summarizeExpenses(
            @ToolParam(description = "项目编号，可选", required = false) String projectCode,
            @ToolParam(description = "支出月份 YYYY-MM，可选", required = false) String month,
            @ToolParam(description = "分组维度 project/month/applicant/status/paymentType，可选", required = false) String groupBy,
            @ToolParam(description = "支出日期起 YYYY-MM-DD，可选", required = false) String dateFrom,
            @ToolParam(description = "支出日期止 YYYY-MM-DD，可选", required = false) String dateTo,
            @ToolParam(description = "单据状态，可多个逗号分隔，默认 approved", required = false) String status,
            @ToolParam(description = "分组条数上限，默认 30，最大 200", required = false) Integer limit,
            ToolContext toolContext) {
        AgentToolContext ctx = AgentToolContext.from(toolContext);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("projectCode", String.valueOf(projectCode));
        args.put("month", String.valueOf(month));
        args.put("groupBy", String.valueOf(groupBy));
        ToolReporting.start(ctx, TOOL_SUMMARIZE, args);
        try {
            LocalDate from = parseDate(dateFrom, "dateFrom");
            LocalDate to = parseDate(dateTo, "dateTo");
            ExpenseQueryFilter filter = new ExpenseQueryFilter(
                    projectCode, scopedApplicant(ctx), month, from, to, parseStatuses(status));

            var summary = expenseAnalysisService.summarize(filter, groupBy, clampLimit(limit));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("groupBy", summary.groupBy());
            result.put("statusScope", summary.statusScope());
            result.put("scope", scopeLabel(ctx));
            result.put("rows", summary.rows());
            result.put("groupCount", summary.groupCount());
            result.put("totalCount", summary.totalCount());
            result.put("grandTotal", summary.grandTotal());
            ToolReporting.success(ctx, TOOL_SUMMARIZE);
            return json(result);
        } catch (IllegalArgumentException exception) {
            ToolReporting.failure(ctx, TOOL_SUMMARIZE, exception.getMessage());
            return json(Map.of("ok", false, "error", exception.getMessage()));
        } catch (RuntimeException exception) {
            ToolReporting.failure(ctx, TOOL_SUMMARIZE, exception.getMessage());
            return json(Map.of("ok", false, "error", "统计支出失败：" + exception.getMessage()));
        }
    }

    @Tool(name = TOOL_LIST_ITEMS,
            description = "查询支出明细行（只读）：金额、支出日期、供应商、发票号、用途，并带所属报销单号、项目、申请人和单据状态。"
                    + "过滤参数与 summarize_expenses 相同（projectCode/month/dateFrom/dateTo/status，默认只看 approved）。"
                    + "用于回答“具体是哪几笔”；金额统计请用 summarize_expenses，不要自己累加。")
    public String listExpenseItems(
            @ToolParam(description = "项目编号，可选", required = false) String projectCode,
            @ToolParam(description = "支出月份 YYYY-MM，可选", required = false) String month,
            @ToolParam(description = "支出日期起 YYYY-MM-DD，可选", required = false) String dateFrom,
            @ToolParam(description = "支出日期止 YYYY-MM-DD，可选", required = false) String dateTo,
            @ToolParam(description = "单据状态，可多个逗号分隔，默认 approved", required = false) String status,
            @ToolParam(description = "返回条数上限，默认 30，最大 200", required = false) Integer limit,
            ToolContext toolContext) {
        AgentToolContext ctx = AgentToolContext.from(toolContext);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("projectCode", String.valueOf(projectCode));
        args.put("month", String.valueOf(month));
        ToolReporting.start(ctx, TOOL_LIST_ITEMS, args);
        try {
            LocalDate from = parseDate(dateFrom, "dateFrom");
            LocalDate to = parseDate(dateTo, "dateTo");
            ExpenseQueryFilter filter = new ExpenseQueryFilter(
                    projectCode, scopedApplicant(ctx), month, from, to, parseStatuses(status));
            int size = clampLimit(limit);
            List<ExpenseItemRowTO> rows = expenseAnalysisService.listItems(filter, size);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("statusScope", expenseAnalysisService.defaultStatuses());
            result.put("scope", scopeLabel(ctx));
            result.put("returned", rows.size());
            result.put("items", rows);
            ToolReporting.success(ctx, TOOL_LIST_ITEMS);
            return json(result);
        } catch (IllegalArgumentException exception) {
            ToolReporting.failure(ctx, TOOL_LIST_ITEMS, exception.getMessage());
            return json(Map.of("ok", false, "error", exception.getMessage()));
        } catch (RuntimeException exception) {
            ToolReporting.failure(ctx, TOOL_LIST_ITEMS, exception.getMessage());
            return json(Map.of("ok", false, "error", "查询支出明细失败：" + exception.getMessage()));
        }
    }

    // ---------- scope & parsing helpers ----------

    /** Non-privileged roles only ever see their own expenses. */
    private static String scopedApplicant(AgentToolContext ctx) {
        CurrentUserTO user = ctx == null ? null : ctx.currentUser();
        if (user == null) {
            return null;
        }
        if (user.hasRole("ADMIN") || user.hasRole("APPROVER")) {
            return null;
        }
        return user.username();
    }

    private static String scopeLabel(AgentToolContext ctx) {
        return scopedApplicant(ctx) == null ? "全实验室" : "仅本人报销";
    }

    private static List<String> parseStatuses(String status) {
        if (!StringUtils.hasText(status)) {
            return List.of();
        }
        return Arrays.stream(status.split("[,，\\s]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private static LocalDate parseDate(String value, String field) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(field + " 必须是 YYYY-MM-DD 格式，收到：" + value);
        }
    }

    private static int clampLimit(Integer limit) {
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
