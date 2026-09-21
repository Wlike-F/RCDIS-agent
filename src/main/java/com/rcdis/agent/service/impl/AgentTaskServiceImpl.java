package com.rcdis.agent.service.impl;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.common.aop.AuditOperation;
import com.rcdis.agent.agent.AgentMetrics;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.ProjectPageRequest;
import com.rcdis.agent.dto.ReimbursementActionRequest;
import com.rcdis.agent.dto.ReimbursementPageRequest;
import com.rcdis.agent.entity.AgentTaskEntity;
import com.rcdis.agent.entity.AgentTaskStepEntity;
import com.rcdis.agent.mapper.AgentTaskMapper;
import com.rcdis.agent.mapper.AgentTaskStepMapper;
import com.rcdis.agent.service.AgentTaskService;
import com.rcdis.agent.service.ReimbursementApprovalService;
import com.rcdis.agent.service.ReimbursementService;
import com.rcdis.agent.service.ResearchProjectService;
import com.rcdis.agent.vo.AgentTaskVO;
import com.rcdis.agent.vo.MaterialCheckVO;
import com.rcdis.agent.vo.ProjectVO;
import com.rcdis.agent.vo.ReimbursementVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Executes a deliberately narrow, auditable batch submission plan; it is not a free-form loop. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskServiceImpl implements AgentTaskService {

    private static final int MAX_PLAN_STEPS = 50;
    private static final int MAX_PLAN_SCAN_RECORDS = 100;
    private static final String STEP_ACTION = "SUBMIT_REIMBURSEMENT";
    private static final int LEASE_MINUTES = 5;

    private final AgentTaskMapper taskMapper;
    private final AgentTaskStepMapper stepMapper;
    private final ResearchProjectService researchProjectService;
    private final ReimbursementService reimbursementService;
    private final ReimbursementApprovalService reimbursementApprovalService;
    private final ObjectMapper objectMapper;
    private final AgentMetrics agentMetrics;

    @Override
    @Transactional
    @AuditOperation(action = "PLAN_REIMBURSEMENT_SUBMISSIONS", targetType = "AGENT_TASK")
    public AgentTaskVO planReimbursementSubmissions(
            String conversationId, String projectCode, String reason, CurrentUserTO actor) {
        if (!StringUtils.hasText(conversationId)) {
            throw new BusinessException("AGENT_TASK_CONVERSATION_REQUIRED", "生成执行计划需要有效 conversationId");
        }
        if (!StringUtils.hasText(projectCode)) {
            throw new BusinessException("AGENT_TASK_PROJECT_REQUIRED", "生成执行计划需要项目编号 projectCode");
        }
        CurrentUserTO user = actor == null ? CurrentUserContextHolder.currentOrAnonymous() : actor;
        ProjectVO project = resolveProject(projectCode.trim());
        if (project == null) {
            throw new BusinessException("AGENT_TASK_PROJECT_NOT_FOUND", "未找到项目编号 " + projectCode);
        }

        PageResponse<ReimbursementVO> page = reimbursementService.pageReimbursements(
                new ReimbursementPageRequest(1, MAX_PLAN_SCAN_RECORDS, project.id(), null, null));
        List<ReimbursementVO> candidates = page.records().stream()
                .filter(order -> "draft".equalsIgnoreCase(order.status()))
                .filter(order -> user.hasRole("ADMIN") || user.username().equals(order.applicant()))
                .limit(MAX_PLAN_STEPS)
                .toList();

        List<ReimbursementVO> eligible = new ArrayList<>();
        Map<Long, MaterialCheckVO> checks = new LinkedHashMap<>();
        for (ReimbursementVO order : candidates) {
            MaterialCheckVO check = reimbursementService.checkMaterials(order.id());
            checks.put(order.id(), check);
            if (check.pass()) {
                eligible.add(order);
            }
        }
        if (eligible.isEmpty()) {
            throw new BusinessException(
                    "AGENT_TASK_NO_ELIGIBLE_REIMBURSEMENTS",
                    "项目 " + project.projectCode() + " 没有属于当前用户且材料齐全的草稿报销单");
        }

        AgentTaskEntity task = new AgentTaskEntity();
        task.setConversationId(conversationId);
        task.setTaskType(AgentTaskEntity.TYPE_REIMBURSEMENT_SUBMISSION);
        task.setTitle("批量提交项目 " + project.projectCode() + " 的材料齐全报销单");
        task.setStatus(AgentTaskEntity.STATUS_PLANNED);
        task.setInputJson(json(Map.of(
                "projectId", project.id(),
                "projectCode", project.projectCode(),
                "reason", reason == null ? "" : reason)));
        task.setPlanJson(json(Map.of(
                "candidateCount", candidates.size(),
                "eligibleCount", eligible.size(),
                "checks", checks)));
        task.setTotalSteps(eligible.size());
        task.setCompletedSteps(0);
        task.setFailedSteps(0);
        task.setVersion(0);
        task.setExecutionAttempts(0);
        task.setCreatedBy(user.userId());
        task.setUpdatedBy(user.userId());
        taskMapper.insert(task);

        int stepNo = 1;
        for (ReimbursementVO order : eligible) {
            AgentTaskStepEntity step = new AgentTaskStepEntity();
            step.setTaskId(task.getId());
            step.setStepNo(stepNo++);
            step.setAction(STEP_ACTION);
            step.setTargetType("REIMBURSEMENT_ORDER");
            step.setTargetId(String.valueOf(order.id()));
            step.setInputJson(json(Map.of(
                    "reimbursementId", order.id(),
                    "reimbursementNo", order.reimbursementNo(),
                    "reason", reason == null ? "Agent 批量计划提交" : reason)));
            step.setStatus(AgentTaskStepEntity.STATUS_PLANNED);
            step.setVersion(0);
            step.setExecutionAttempts(0);
            step.setCreatedBy(user.userId());
            step.setUpdatedBy(user.userId());
            stepMapper.insert(step);
        }
        task.setStatus(AgentTaskEntity.STATUS_WAITING_CONFIRMATION);
        taskMapper.updateById(task);
        return toVO(task, listSteps(task.getId()));
    }

    @Override
    @AuditOperation(action = "EXECUTE_REIMBURSEMENT_PLAN", targetType = "AGENT_TASK")
    public AgentTaskVO execute(Long taskId) {
        AgentTaskEntity task = loadOwnedTask(taskId);
        boolean expiredRunning = AgentTaskEntity.STATUS_RUNNING.equals(task.getStatus())
                && task.getLeaseUntil() != null && OffsetDateTime.now().isAfter(task.getLeaseUntil());
        if (!expiredRunning && !List.of(AgentTaskEntity.STATUS_WAITING_CONFIRMATION,
                AgentTaskEntity.STATUS_FAILED, AgentTaskEntity.STATUS_PARTIAL).contains(task.getStatus())) {
            throw new BusinessException(
                    "AGENT_TASK_STATUS_CONFLICT",
                    "任务当前状态不可执行，taskId=" + taskId + "，status=" + task.getStatus(),
                    HttpStatus.CONFLICT);
        }
        String executionOwner = UUID.randomUUID().toString();
        if (!claimTask(task, executionOwner, expiredRunning)) {
            throw new BusinessException(
                    "AGENT_TASK_ALREADY_RUNNING",
                    "任务已被其他请求执行或状态已变更，taskId=" + taskId,
                    HttpStatus.CONFLICT);
        }
        task.setStatus(AgentTaskEntity.STATUS_RUNNING);
        task.setStartedAt(OffsetDateTime.now());
        task.setExecutionOwner(executionOwner);
        task.setLeaseUntil(OffsetDateTime.now().plusMinutes(LEASE_MINUTES));
        task.setExecutionAttempts(task.getExecutionAttempts() == null ? 1 : task.getExecutionAttempts() + 1);
        task.setErrorMessage(null);

        List<AgentTaskStepEntity> steps = listSteps(taskId);
        for (AgentTaskStepEntity step : steps) {
            heartbeatTask(task, executionOwner);
            executeStep(step);
        }
        return finishTask(task, listSteps(taskId));
    }

    @Override
    @AuditOperation(action = "RETRY_REIMBURSEMENT_PLAN", targetType = "AGENT_TASK")
    public AgentTaskVO prepareRetry(Long taskId) {
        AgentTaskEntity task = loadOwnedTask(taskId);
        if (!List.of(AgentTaskEntity.STATUS_FAILED, AgentTaskEntity.STATUS_PARTIAL).contains(task.getStatus())) {
            throw new BusinessException(
                    "AGENT_TASK_RETRY_NOT_ALLOWED",
                    "只有 FAILED 或 PARTIAL 任务可重试，taskId=" + taskId,
                    HttpStatus.CONFLICT);
        }
        return toVO(task, listSteps(taskId));
    }

    @Override
    public AgentTaskVO get(Long taskId) {
        AgentTaskEntity task = loadOwnedTask(taskId);
        return toVO(task, listSteps(taskId));
    }

    private void executeStep(AgentTaskStepEntity step) {
        boolean expiredRunning = AgentTaskStepEntity.STATUS_RUNNING.equals(step.getStatus())
                && step.getLeaseUntil() != null && OffsetDateTime.now().isAfter(step.getLeaseUntil());
        if (!expiredRunning && !List.of(AgentTaskStepEntity.STATUS_PLANNED,
                AgentTaskStepEntity.STATUS_FAILED).contains(step.getStatus())) {
            return;
        }
        String executionOwner = UUID.randomUUID().toString();
        if (!claimStep(step, executionOwner, expiredRunning)) {
            return;
        }
        step.setStatus(AgentTaskStepEntity.STATUS_RUNNING);
        step.setStartedAt(OffsetDateTime.now());
        step.setExecutionOwner(executionOwner);
        step.setLeaseUntil(OffsetDateTime.now().plusMinutes(LEASE_MINUTES));
        step.setExecutionAttempts(step.getExecutionAttempts() == null ? 1 : step.getExecutionAttempts() + 1);
        step.setErrorMessage(null);
        try {
            Long reimbursementId = Long.valueOf(step.getTargetId());
            var current = reimbursementService.getReimbursement(reimbursementId);
            if (!"draft".equalsIgnoreCase(current.order().status())
                    && !"rejected".equalsIgnoreCase(current.order().status())) {
                completeReconciledStep(step, current.order().reimbursementNo(), current.order().status());
                return;
            }
            var detail = reimbursementApprovalService.submit(
                    reimbursementId, new ReimbursementActionRequest("Agent 批量计划确认执行"));
            step.setStatus(AgentTaskStepEntity.STATUS_SUCCEEDED);
            step.setOutputJson(json(Map.of(
                    "reimbursementId", reimbursementId,
                    "reimbursementNo", detail.order().reimbursementNo(),
                    "status", detail.order().status())));
            step.setCompletedAt(OffsetDateTime.now());
            step.setLeaseUntil(null);
            stepMapper.updateById(step);
            agentMetrics.recordTaskStep(AgentTaskStepEntity.STATUS_SUCCEEDED);
        } catch (RuntimeException exception) {
            step.setStatus(AgentTaskStepEntity.STATUS_FAILED);
            step.setErrorMessage(truncate(exception.getMessage()));
            step.setCompletedAt(OffsetDateTime.now());
            step.setLeaseUntil(null);
            stepMapper.updateById(step);
            agentMetrics.recordTaskStep(AgentTaskStepEntity.STATUS_FAILED);
            log.atWarn()
                    .setCause(exception)
                    .addKeyValue("taskId", step.getTaskId())
                    .addKeyValue("stepId", step.getId())
                    .addKeyValue("targetId", step.getTargetId())
                    .log("Agent task step failed");
        }
    }

    private boolean claimTask(AgentTaskEntity task, String executionOwner, boolean expiredRunning) {
        OffsetDateTime now = OffsetDateTime.now();
        LambdaUpdateWrapper<AgentTaskEntity> update = new LambdaUpdateWrapper<>();
        update.eq(AgentTaskEntity::getId, task.getId())
                .eq(AgentTaskEntity::getStatus, task.getStatus())
                .lt(expiredRunning, AgentTaskEntity::getLeaseUntil, now)
                .set(AgentTaskEntity::getStatus, AgentTaskEntity.STATUS_RUNNING)
                .set(AgentTaskEntity::getStartedAt, now)
                .set(AgentTaskEntity::getExecutionOwner, executionOwner)
                .set(AgentTaskEntity::getLeaseUntil, now.plusMinutes(LEASE_MINUTES))
                .setSql("execution_attempts = execution_attempts + 1")
                .set(AgentTaskEntity::getErrorMessage, null)
                .set(AgentTaskEntity::getUpdatedAt, now);
        return taskMapper.update(null, update) == 1;
    }

    private boolean claimStep(AgentTaskStepEntity step, String executionOwner, boolean expiredRunning) {
        OffsetDateTime now = OffsetDateTime.now();
        LambdaUpdateWrapper<AgentTaskStepEntity> update = new LambdaUpdateWrapper<>();
        update.eq(AgentTaskStepEntity::getId, step.getId())
                .eq(AgentTaskStepEntity::getStatus, step.getStatus())
                .lt(expiredRunning, AgentTaskStepEntity::getLeaseUntil, now)
                .set(AgentTaskStepEntity::getStatus, AgentTaskStepEntity.STATUS_RUNNING)
                .set(AgentTaskStepEntity::getStartedAt, now)
                .set(AgentTaskStepEntity::getExecutionOwner, executionOwner)
                .set(AgentTaskStepEntity::getLeaseUntil, now.plusMinutes(LEASE_MINUTES))
                .setSql("execution_attempts = execution_attempts + 1")
                .set(AgentTaskStepEntity::getErrorMessage, null)
                .set(AgentTaskStepEntity::getUpdatedAt, now);
        return stepMapper.update(null, update) == 1;
    }

    private void heartbeatTask(AgentTaskEntity task, String executionOwner) {
        OffsetDateTime now = OffsetDateTime.now();
        LambdaUpdateWrapper<AgentTaskEntity> update = new LambdaUpdateWrapper<>();
        update.eq(AgentTaskEntity::getId, task.getId())
                .eq(AgentTaskEntity::getStatus, AgentTaskEntity.STATUS_RUNNING)
                .eq(AgentTaskEntity::getExecutionOwner, executionOwner)
                .set(AgentTaskEntity::getLeaseUntil, now.plusMinutes(LEASE_MINUTES))
                .set(AgentTaskEntity::getUpdatedAt, now);
        if (taskMapper.update(null, update) != 1) {
            throw new BusinessException("AGENT_TASK_LEASE_LOST", "任务执行租约已丢失，taskId=" + task.getId());
        }
    }

    private void completeReconciledStep(AgentTaskStepEntity step, String reimbursementNo, String status) {
        step.setStatus(AgentTaskStepEntity.STATUS_SUCCEEDED);
        step.setOutputJson(json(Map.of(
                "reimbursementId", Long.valueOf(step.getTargetId()),
                "reimbursementNo", reimbursementNo,
                "status", status,
                "reconciled", true)));
        step.setErrorMessage(null);
        step.setCompletedAt(OffsetDateTime.now());
        step.setLeaseUntil(null);
        stepMapper.updateById(step);
    }

    private AgentTaskVO finishTask(AgentTaskEntity task, List<AgentTaskStepEntity> steps) {
        int succeeded = (int) steps.stream()
                .filter(step -> AgentTaskStepEntity.STATUS_SUCCEEDED.equals(step.getStatus())).count();
        int failed = (int) steps.stream()
                .filter(step -> AgentTaskStepEntity.STATUS_FAILED.equals(step.getStatus())).count();
        task.setCompletedSteps(succeeded);
        task.setFailedSteps(failed);
        task.setCompletedAt(OffsetDateTime.now());
        task.setLeaseUntil(null);
        if (failed == 0) {
            task.setStatus(AgentTaskEntity.STATUS_SUCCEEDED);
        } else if (succeeded == 0) {
            task.setStatus(AgentTaskEntity.STATUS_FAILED);
            task.setErrorMessage("所有步骤执行失败，可重新生成重试提案");
        } else {
            task.setStatus(AgentTaskEntity.STATUS_PARTIAL);
            task.setErrorMessage("部分步骤执行失败，可重新生成重试提案");
        }
        taskMapper.updateById(task);
        return toVO(task, steps);
    }

    private AgentTaskEntity loadOwnedTask(Long taskId) {
        AgentTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("AGENT_TASK_NOT_FOUND", "Agent 任务不存在，taskId=" + taskId);
        }
        CurrentUserTO user = CurrentUserContextHolder.currentOrAnonymous();
        if (!user.hasRole("ADMIN") && !user.userId().equals(task.getCreatedBy())) {
            throw new BusinessException("AGENT_TASK_FORBIDDEN", "该任务属于其他用户", HttpStatus.FORBIDDEN);
        }
        return task;
    }

    private ProjectVO resolveProject(String projectCode) {
        return researchProjectService.pageProjects(new ProjectPageRequest(1, 20, projectCode, null))
                .records().stream()
                .filter(project -> projectCode.equalsIgnoreCase(project.projectCode()))
                .findFirst()
                .orElse(null);
    }

    private List<AgentTaskStepEntity> listSteps(Long taskId) {
        return stepMapper.selectList(new LambdaQueryWrapper<AgentTaskStepEntity>()
                .eq(AgentTaskStepEntity::getTaskId, taskId)
                .orderByAsc(AgentTaskStepEntity::getStepNo));
    }

    private AgentTaskVO toVO(AgentTaskEntity task, List<AgentTaskStepEntity> steps) {
        List<AgentTaskVO.StepVO> stepVOs = steps.stream().map(step -> new AgentTaskVO.StepVO(
                step.getId(), step.getStepNo(), step.getAction(), step.getTargetType(), step.getTargetId(),
                step.getStatus(), step.getOutputJson(), step.getErrorMessage(),
                step.getStartedAt(), step.getCompletedAt())).toList();
        return new AgentTaskVO(
                task.getId(), task.getConversationId(), task.getTaskType(), task.getTitle(), task.getStatus(),
                task.getTotalSteps(), task.getCompletedSteps(), task.getFailedSteps(), task.getErrorMessage(),
                task.getStartedAt(), task.getCompletedAt(), task.getCreatedAt(), stepVOs);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BusinessException("AGENT_TASK_SERIALIZE_FAILED", "Agent 任务数据序列化失败", exception);
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return "Unknown task step failure";
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
