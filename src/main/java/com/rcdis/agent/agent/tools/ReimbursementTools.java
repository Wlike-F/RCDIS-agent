package com.rcdis.agent.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.agent.AgentToolContext;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.ProjectPageRequest;
import com.rcdis.agent.dto.ReimbursementItemInput;
import com.rcdis.agent.dto.ReimbursementPageRequest;
import com.rcdis.agent.service.ReimbursementService;
import com.rcdis.agent.service.ResearchProjectService;
import com.rcdis.agent.to.AgentProposalTO;
import com.rcdis.agent.vo.MaterialCheckVO;
import com.rcdis.agent.vo.ProjectVO;
import com.rcdis.agent.vo.ReimbursementDetailVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-only reimbursement tools. Delegates entirely to domain services; never touches mappers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReimbursementTools {

    private static final String TOOL_LIST_REIMBURSEMENTS = "list_reimbursements";
    private static final String TOOL_CHECK_MATERIALS = "check_reimbursement_materials";
    private static final String TOOL_SUMMARY = "generate_reimbursement_summary";
    private static final String TOOL_SUBMIT = "submit_reimbursement";
    private static final String TOOL_CREATE = "create_reimbursement";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    /**
     * Placeholder strings the model tends to fill for absent optional fields (e.g. "N/A").
     * They must become null so downstream receipt validation only runs on genuine references.
     */
    private static final Set<String> PLACEHOLDER_VALUES = Set.of(
            "n/a", "na", "none", "null", "nil", "-", "—", "–", "无", "没有", "暂无");

    private final ReimbursementService reimbursementService;
    private final ResearchProjectService researchProjectService;
    private final ProposalSupport proposalSupport;
    private final ObjectMapper objectMapper;

    @Tool(name = TOOL_LIST_REIMBURSEMENTS,
            description = "查询报销单列表（只读）。可选过滤：projectCode 项目编号、status 报销状态"
                    + "（DRAFT 草稿 / SUBMITTED 已提交 / APPROVED 已通过 / REJECTED 已驳回）、limit 条数上限（默认 20）。"
                    + "返回报销单号、项目、申请人、总金额、明细条数、状态、提交/审批时间。")
    public String listReimbursements(
            @ToolParam(description = "项目编号，可选", required = false) String projectCode,
            @ToolParam(description = "报销状态 DRAFT/SUBMITTED/APPROVED/REJECTED，可选", required = false) String status,
            @ToolParam(description = "返回条数上限，默认 20，最大 50", required = false) Integer limit,
            ToolContext toolContext) {
        AgentToolContext ctx = AgentToolContext.from(toolContext);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("projectCode", String.valueOf(projectCode));
        args.put("status", String.valueOf(status));
        ToolReporting.start(ctx, TOOL_LIST_REIMBURSEMENTS, args);
        try {
            Long projectId = null;
            if (StringUtils.hasText(projectCode)) {
                ProjectVO project = resolveProjectByCode(projectCode.trim());
                if (project == null) {
                    ToolReporting.failure(ctx, TOOL_LIST_REIMBURSEMENTS, "项目不存在");
                    return json(Map.of("ok", false, "error", "未找到项目编号 " + projectCode));
                }
                projectId = project.id();
            }
            int size = clampLimit(limit);
            String normalizedStatus = StringUtils.hasText(status) ? status.trim().toUpperCase() : null;
            PageResponse<com.rcdis.agent.vo.ReimbursementVO> page = reimbursementService.pageReimbursements(
                    new ReimbursementPageRequest(1, size, projectId, normalizedStatus, null));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("total", page.total());
            result.put("returned", page.records().size());
            result.put("reimbursements", page.records());
            ToolReporting.success(ctx, TOOL_LIST_REIMBURSEMENTS);
            return json(result);
        } catch (RuntimeException exception) {
            ToolReporting.failure(ctx, TOOL_LIST_REIMBURSEMENTS, exception.getMessage());
            return json(Map.of("ok", false, "error", "查询报销单失败：" + exception.getMessage()));
        }
    }

    @Tool(name = TOOL_CHECK_MATERIALS,
            description = "检查某张报销单的报销材料是否齐备（只读）。返回 pass 是否通过、checkedCount 检查条数、"
                    + "findings 缺项/警告明细（level 为 MISSING 表示缺项会阻断提交，WARNING 仅提示）。"
                    + "参数 reimbursementId 为报销单 id，可先通过 list_reimbursements 获取。")
    public String checkReimbursementMaterials(
            @ToolParam(description = "报销单 id") Long reimbursementId,
            ToolContext toolContext) {
        AgentToolContext ctx = AgentToolContext.from(toolContext);
        ToolReporting.start(ctx, TOOL_CHECK_MATERIALS, Map.of("reimbursementId", String.valueOf(reimbursementId)));
        try {
            if (reimbursementId == null) {
                ToolReporting.failure(ctx, TOOL_CHECK_MATERIALS, "缺少报销单 id");
                return json(Map.of("ok", false, "error", "请提供报销单 id reimbursementId"));
            }
            MaterialCheckVO check = reimbursementService.checkMaterials(reimbursementId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("reimbursementId", reimbursementId);
            result.put("pass", check.pass());
            result.put("checkedCount", check.checkedCount());
            result.put("findings", check.findings());
            ToolReporting.success(ctx, TOOL_CHECK_MATERIALS);
            return json(result);
        } catch (RuntimeException exception) {
            ToolReporting.failure(ctx, TOOL_CHECK_MATERIALS, exception.getMessage());
            return json(Map.of("ok", false, "error", "材料检查失败：" + exception.getMessage()));
        }
    }

    @Tool(name = TOOL_SUMMARY,
            description = "生成某张报销单的结构化汇总（只读）：报销单基本信息 + 逐条明细（支出、金额、科目）。"
                    + "用于向用户解释一张报销单包含什么。参数 reimbursementId 为报销单 id。")
    public String generateReimbursementSummary(
            @ToolParam(description = "报销单 id") Long reimbursementId,
            ToolContext toolContext) {
        AgentToolContext ctx = AgentToolContext.from(toolContext);
        ToolReporting.start(ctx, TOOL_SUMMARY, Map.of("reimbursementId", String.valueOf(reimbursementId)));
        try {
            if (reimbursementId == null) {
                ToolReporting.failure(ctx, TOOL_SUMMARY, "缺少报销单 id");
                return json(Map.of("ok", false, "error", "请提供报销单 id reimbursementId"));
            }
            ReimbursementDetailVO detail = reimbursementService.getReimbursement(reimbursementId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("order", detail.order());
            result.put("items", detail.items());
            ToolReporting.success(ctx, TOOL_SUMMARY);
            return json(result);
        } catch (RuntimeException exception) {
            ToolReporting.failure(ctx, TOOL_SUMMARY, exception.getMessage());
            return json(Map.of("ok", false, "error", "生成报销汇总失败：" + exception.getMessage()));
        }
    }

    @Tool(name = TOOL_SUBMIT,
            description = "【高风险·需确认】提议把一张草稿报销单提交进入审批流程。不会立即写库：生成待确认提案，用户确认后才执行。"
                    + "入参：reimbursementId 报销单 id（可先通过 list_reimbursements 获取）、reason 可选说明。"
                    + "提交前建议先调用 check_reimbursement_materials 确认材料齐备。")
    public String submitReimbursement(
            @ToolParam(description = "报销单 id") Long reimbursementId,
            @ToolParam(description = "提交说明，可选", required = false) String reason,
            ToolContext toolContext) {
        AgentToolContext ctx = AgentToolContext.from(toolContext);
        ToolReporting.start(ctx, TOOL_SUBMIT, Map.of("reimbursementId", String.valueOf(reimbursementId)));
        try {
            if (reimbursementId == null) {
                ToolReporting.failure(ctx, TOOL_SUBMIT, "缺少报销单 id");
                return json(Map.of("ok", false, "error", "请提供报销单 id reimbursementId"));
            }
            ReimbursementDetailVO current = reimbursementService.getReimbursement(reimbursementId);
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("reimbursementId", reimbursementId);
            arguments.put("reason", reason);

            String summary = "提交报销单 " + current.order().reimbursementNo()
                    + "（金额 " + current.order().totalAmount() + "，" + current.items().size() + " 条明细）进入审批";
            String result = proposalSupport.propose(ctx, new AgentProposalTO(
                    ctx == null ? null : ctx.conversationId(),
                    TOOL_SUBMIT,
                    arguments,
                    summary,
                    "REIMBURSEMENT",
                    String.valueOf(reimbursementId),
                    proposalSupport.snapshot(current.order()),
                    proposalSupport.snapshot(arguments),
                    reason,
                    "将把报销单从草稿提交进入审批流程"));
            ToolReporting.success(ctx, TOOL_SUBMIT);
            return result;
        } catch (RuntimeException exception) {
            ToolReporting.failure(ctx, TOOL_SUBMIT, exception.getMessage());
            return json(Map.of("ok", false, "error", "生成提交报销提案失败：" + exception.getMessage()));
        }
    }

    @Tool(name = TOOL_CREATE,
            description = "【高风险·需确认】在项目下创建一张报销单，明细自带金额/日期/供应商/发票/用途等。不会立即写库：生成待确认提案，"
                    + "用户确认后才执行。paymentType：reimbursement 走审批；public_payment 公卡直接支付、提交即入账免审批。"
                    + "入参：projectCode 项目编号、paymentType 支付方式、items 明细行列表（每行 amount/expenseDate(YYYY-MM-DD)/description，"
                    + "报销行还需 vendor 与 invoiceNo 或 receiptFile，公卡行需 counterpartyAccount；无凭证时 receiptFile 必须留空，"
                    + "禁止填 N/A/无 等占位符，聊天窗口上传的附件不能作为 receiptFile）、reason 原因；可选 submitNow（默认 false）。"
                    + "申请人自动取当前登录用户。记一笔公卡支出即 paymentType=public_payment 且 submitNow=true。")
    public String createReimbursement(
            @ToolParam(description = "项目编号，例如 P-2026-001") String projectCode,
            @ToolParam(description = "支付方式 reimbursement / public_payment") String paymentType,
            @ToolParam(description = "报销明细行列表") List<ReimbursementItemInput> items,
            @ToolParam(description = "创建原因，将写入审计日志") String reason,
            @ToolParam(description = "是否创建后立即提交/入账，默认 false", required = false) Boolean submitNow,
            ToolContext toolContext) {
        AgentToolContext ctx = AgentToolContext.from(toolContext);
        ToolReporting.start(ctx, TOOL_CREATE, Map.of(
                "projectCode", String.valueOf(projectCode),
                "paymentType", String.valueOf(paymentType)));
        try {
            if (items == null || items.isEmpty()) {
                ToolReporting.failure(ctx, TOOL_CREATE, "缺少明细行");
                return json(Map.of("ok", false, "error", "请至少提供一条报销明细 items"));
            }
            String normalizedType = paymentType == null ? "" : paymentType.trim().toLowerCase();
            if (!"reimbursement".equals(normalizedType) && !"public_payment".equals(normalizedType)) {
                ToolReporting.failure(ctx, TOOL_CREATE, "支付方式非法");
                return json(Map.of("ok", false, "error", "paymentType 必须是 reimbursement 或 public_payment"));
            }
            ProjectVO project = resolveProjectByCode(projectCode == null ? null : projectCode.trim());
            if (project == null) {
                ToolReporting.failure(ctx, TOOL_CREATE, "项目不存在");
                return json(Map.of("ok", false, "error", "未找到项目编号 " + projectCode));
            }
            String applicant = ctx == null || ctx.currentUser() == null ? null : ctx.currentUser().username();
            boolean publicPayment = "public_payment".equals(normalizedType);

            List<ReimbursementItemInput> normalizedItems = normalizeItems(items);
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("projectId", project.id());
            arguments.put("applicant", applicant);
            arguments.put("paymentType", normalizedType);
            arguments.put("items", normalizedItems);
            arguments.put("reason", reason);
            arguments.put("submitNow", Boolean.TRUE.equals(submitNow));

            String summary = "在项目 " + project.projectCode() + " 用 " + items.size() + " 条明细创建"
                    + (publicPayment ? "公卡支付单" : "报销单")
                    + (Boolean.TRUE.equals(submitNow) ? "并立即提交/入账" : "（草稿）");
            String result = proposalSupport.propose(ctx, new AgentProposalTO(
                    ctx == null ? null : ctx.conversationId(),
                    TOOL_CREATE,
                    arguments,
                    summary,
                    "REIMBURSEMENT",
                    "(新建)",
                    null,
                    proposalSupport.snapshot(arguments),
                    reason,
                    "将新建一张" + (publicPayment ? "公卡支付单（提交即入账）" : "报销单（草稿）")));
            ToolReporting.success(ctx, TOOL_CREATE);
            return result;
        } catch (RuntimeException exception) {
            ToolReporting.failure(ctx, TOOL_CREATE, exception.getMessage());
            return json(Map.of("ok", false, "error", "生成创建报销单提案失败：" + exception.getMessage()));
        }
    }

    /** Replaces model-filled placeholder values with null so absent optional fields stay absent. */
    static List<ReimbursementItemInput> normalizeItems(List<ReimbursementItemInput> items) {
        List<ReimbursementItemInput> normalized = new ArrayList<>(items.size());
        for (ReimbursementItemInput item : items) {
            normalized.add(new ReimbursementItemInput(
                    item.amount(),
                    item.expenseDate(),
                    normalizeText(item.vendor()),
                    normalizeText(item.invoiceNo()),
                    normalizeText(item.receiptFile()),
                    item.description(),
                    normalizeText(item.counterpartyAccount())));
        }
        return normalized;
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || PLACEHOLDER_VALUES.contains(trimmed.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return trimmed;
    }

    private ProjectVO resolveProjectByCode(String code) {
        PageResponse<ProjectVO> page = researchProjectService.pageProjects(
                new ProjectPageRequest(1, 20, code, null));
        return page.records().stream()
                .filter(vo -> code.equalsIgnoreCase(vo.projectCode()))
                .findFirst()
                .orElse(null);
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
