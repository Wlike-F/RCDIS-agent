package com.rcdis.agent.agent.tools;

import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.rcdis.agent.agent.AgentToolContext;
import com.rcdis.agent.entity.AgentTaskEntity;
import com.rcdis.agent.service.AgentTaskService;
import com.rcdis.agent.to.AgentProposalTO;
import com.rcdis.agent.vo.AgentTaskVO;

import lombok.RequiredArgsConstructor;

/** Bounded Plan-and-Execute tools; neither method mutates financial records before confirmation. */
@Component
@RequiredArgsConstructor
public class PlanTools {

    private static final String TOOL_PLAN = "plan_reimbursement_submissions";
    private static final String TOOL_RETRY = "retry_reimbursement_plan";
    private static final String EXECUTE_ACTION = "execute_reimbursement_plan";

    private final AgentTaskService agentTaskService;
    private final ProposalSupport proposalSupport;

    @Tool(name = TOOL_PLAN,
            description = "【复合任务·需整体确认】查询指定项目中属于当前用户的草稿报销单，逐单检查材料，"
                    + "只把材料齐全的报销单组成确定性提交计划。生成 task/step 和整体确认卡片；确认前不会提交任何报销单。"
                    + "参数 projectCode 为项目编号，reason 为计划原因。最多规划 50 个步骤。")
    public String planReimbursementSubmissions(
            @ToolParam(description = "项目编号") String projectCode,
            @ToolParam(description = "批量提交原因") String reason,
            ToolContext toolContext) {
        AgentToolContext context = AgentToolContext.from(toolContext);
        ToolReporting.start(context, TOOL_PLAN, Map.of(
                "projectCode", String.valueOf(projectCode), "reason", String.valueOf(reason)));
        try {
            AgentTaskVO task = agentTaskService.planReimbursementSubmissions(
                    context == null ? null : context.conversationId(), projectCode, reason,
                    context == null ? null : context.currentUser());
            String result = proposalSupport.propose(context, new AgentProposalTO(
                    context == null ? null : context.conversationId(),
                    EXECUTE_ACTION,
                    Map.of("taskId", task.id()),
                    "执行任务 #" + task.id() + "：" + task.title() + "，共 " + task.totalSteps() + " 个步骤",
                    "AGENT_TASK",
                    String.valueOf(task.id()),
                    proposalSupport.snapshot(task),
                    proposalSupport.snapshot(Map.of("status", AgentTaskEntity.STATUS_RUNNING)),
                    reason,
                    "整体确认后按顺序提交材料齐全的草稿报销单；每一步独立记录结果"));
            ToolReporting.success(context, TOOL_PLAN);
            return result;
        } catch (RuntimeException exception) {
            ToolReporting.failure(context, TOOL_PLAN, exception.getMessage());
            throw exception;
        }
    }

    @Tool(name = TOOL_RETRY,
            description = "【复合任务·需整体确认】为 FAILED/PARTIAL 的批量报销任务生成重试提案。"
                    + "确认后只重试失败步骤，已成功步骤不会重复执行。参数 taskId 为 Agent 任务 id。")
    public String retryReimbursementPlan(
            @ToolParam(description = "Agent 任务 id") Long taskId,
            @ToolParam(description = "重试原因") String reason,
            ToolContext toolContext) {
        AgentToolContext context = AgentToolContext.from(toolContext);
        ToolReporting.start(context, TOOL_RETRY, Map.of("taskId", String.valueOf(taskId)));
        try {
            AgentTaskVO task = agentTaskService.prepareRetry(taskId);
            String result = proposalSupport.propose(context, new AgentProposalTO(
                    context == null ? null : context.conversationId(),
                    EXECUTE_ACTION,
                    Map.of("taskId", task.id()),
                    "重试任务 #" + task.id() + " 的 " + task.failedSteps() + " 个失败步骤",
                    "AGENT_TASK",
                    String.valueOf(task.id()),
                    proposalSupport.snapshot(task),
                    proposalSupport.snapshot(Map.of("retryFailedSteps", true)),
                    reason,
                    "整体确认后仅重试失败步骤，不重复执行成功步骤"));
            ToolReporting.success(context, TOOL_RETRY);
            return result;
        } catch (RuntimeException exception) {
            ToolReporting.failure(context, TOOL_RETRY, exception.getMessage());
            throw exception;
        }
    }
}
