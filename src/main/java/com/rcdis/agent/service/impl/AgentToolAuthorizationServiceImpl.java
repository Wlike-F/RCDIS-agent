package com.rcdis.agent.service.impl;

import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.agent.AgentToolContext;
import com.rcdis.agent.agent.AgentMetrics;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.config.SecurityProperties;
import com.rcdis.agent.service.AgentToolAuthorizationService;
import com.rcdis.agent.service.ReimbursementService;
import com.rcdis.agent.vo.ReimbursementDetailVO;

import lombok.RequiredArgsConstructor;

/** Role and record-scope policy for every tool invocation, independent of prompt compliance. */
@Service
@RequiredArgsConstructor
public class AgentToolAuthorizationServiceImpl implements AgentToolAuthorizationService {

    private static final Set<String> ALL_BUSINESS_ROLES = Set.of("ADMIN", "APPROVER", "RESEARCHER");
    private static final Map<String, Set<String>> ROLE_POLICY = Map.ofEntries(
            Map.entry("list_audit_logs", Set.of("ADMIN", "APPROVER")),
            Map.entry("create_reimbursement", Set.of("ADMIN", "RESEARCHER")),
            Map.entry("submit_reimbursement", Set.of("ADMIN", "RESEARCHER")),
            Map.entry("plan_reimbursement_submissions", Set.of("ADMIN", "RESEARCHER")),
            Map.entry("retry_reimbursement_plan", Set.of("ADMIN", "RESEARCHER")));

    private final ObjectMapper objectMapper;
    private final ReimbursementService reimbursementService;
    private final SecurityProperties securityProperties;
    private final AgentMetrics agentMetrics;

    @Override
    public void authorize(String toolName, String toolInput, AgentToolContext context) {
        CurrentUserTO user = context == null ? null : context.currentUser();
        if (!securityProperties.isAuthRequired() && (user == null || "anonymous".equals(user.userId()))) {
            return;
        }
        if (user == null || "anonymous".equals(user.userId())) {
            throw forbidden(toolName, "未登录用户不能调用 Agent 工具");
        }
        Set<String> allowed = ROLE_POLICY.getOrDefault(toolName, ALL_BUSINESS_ROLES);
        if (allowed.stream().noneMatch(user::hasRole)) {
            throw forbidden(toolName, "当前角色无权调用该工具");
        }
        if ("submit_reimbursement".equals(toolName)) {
            requireReimbursementOwnership(toolName, toolInput, user);
        }
    }

    private void requireReimbursementOwnership(String toolName, String toolInput, CurrentUserTO user) {
        if (user.hasRole("ADMIN")) {
            return;
        }
        Long reimbursementId = readLong(toolInput, "reimbursementId");
        if (reimbursementId == null) {
            return;
        }
        ReimbursementDetailVO detail = reimbursementService.getReimbursement(reimbursementId);
        String applicant = detail.order() == null ? null : detail.order().applicant();
        if (applicant == null || !applicant.equals(user.username())) {
            throw forbidden(toolName, "只能提交本人创建的报销单，reimbursementId=" + reimbursementId);
        }
    }

    private Long readLong(String json, String field) {
        try {
            JsonNode node = objectMapper.readTree(json == null ? "{}" : json).path(field);
            return node.isIntegralNumber() ? node.longValue() : null;
        } catch (Exception exception) {
            throw new BusinessException("AGENT_TOOL_INPUT_INVALID", "工具参数无法解析：" + field);
        }
    }

    private BusinessException forbidden(String toolName, String reason) {
        agentMetrics.recordSecurityBlock("tool_authorization");
        return new BusinessException(
                "AGENT_TOOL_FORBIDDEN",
                reason + "，toolName=" + toolName,
                HttpStatus.FORBIDDEN);
    }
}
