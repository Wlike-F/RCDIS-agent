package com.rcdis.agent.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.agent.AgentMetrics;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.dto.ChatConfirmResponse;
import com.rcdis.agent.dto.ReimbursementActionRequest;
import com.rcdis.agent.dto.ReimbursementCreateRequest;
import com.rcdis.agent.dto.ReimbursementItemInput;
import com.rcdis.agent.entity.AgentPendingActionEntity;
import com.rcdis.agent.mapper.AgentPendingActionMapper;
import com.rcdis.agent.service.AgentPendingActionService;
import com.rcdis.agent.service.ReimbursementApprovalService;
import com.rcdis.agent.service.ReimbursementService;
import com.rcdis.agent.service.AgentTaskService;
import com.rcdis.agent.to.AgentProposalTO;
import com.rcdis.agent.vo.ReimbursementDetailVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Persists Agent write proposals and replays them only after explicit human approval.
 *
 * <p>The confirm step re-checks ownership, {@code PENDING} status and expiry before touching any
 * domain service, and captures optimistic-lock versions at proposal time so a concurrently edited
 * record surfaces as a clean conflict instead of a silent overwrite.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentPendingActionServiceImpl implements AgentPendingActionService {

    private static final String TOOL_SUBMIT_REIMBURSEMENT = "submit_reimbursement";
    private static final String TOOL_CREATE_REIMBURSEMENT = "create_reimbursement";
    private static final String ACTION_EXECUTE_REIMBURSEMENT_PLAN = "execute_reimbursement_plan";
    private static final int ARGUMENTS_MAX_LENGTH = 16_000;
    private static final int SNAPSHOT_MAX_LENGTH = 8_000;
    private static final int SUMMARY_MAX_LENGTH = 1_000;
    private static final int ERROR_MAX_LENGTH = 2_000;
    private static final int LEASE_MINUTES = 5;

    private final AgentPendingActionMapper agentPendingActionMapper;
    private final ReimbursementService reimbursementService;
    private final ReimbursementApprovalService reimbursementApprovalService;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;
    private final AgentTaskService agentTaskService;
    private final TransactionTemplate transactionTemplate;
    private final AgentMetrics agentMetrics;

    @Override
    @Transactional
    public AgentPendingActionEntity createProposal(AgentProposalTO proposal, String actorUserId) {
        AgentPendingActionEntity entity = new AgentPendingActionEntity();
        entity.setConversationId(proposal.conversationId());
        entity.setToolName(proposal.toolName());
        entity.setArgumentsJson(requireWithinLimit(
                writeJson(proposal.arguments()), ARGUMENTS_MAX_LENGTH, "AGENT_PROPOSAL_ARGUMENTS_TOO_LARGE"));
        entity.setSummary(truncate(proposal.summary(), SUMMARY_MAX_LENGTH));
        entity.setTargetType(proposal.targetType());
        entity.setTargetId(proposal.targetId());
        entity.setBeforeSnapshot(truncate(proposal.beforeSnapshot(), SNAPSHOT_MAX_LENGTH));
        entity.setAfterSnapshot(truncate(proposal.afterSnapshot(), SNAPSHOT_MAX_LENGTH));
        entity.setReason(truncate(proposal.reason(), SUMMARY_MAX_LENGTH));
        entity.setScope(truncate(proposal.scope(), SUMMARY_MAX_LENGTH));
        entity.setStatus(AgentPendingActionEntity.STATUS_PENDING);
        entity.setCommandId(UUID.randomUUID().toString());
        entity.setExecutionAttempts(0);
        CurrentUserTO actor = CurrentUserContextHolder.currentOrAnonymous();
        entity.setActorUsername(actor.username());
        entity.setActorRoles(String.join(",", actor.roles()));
        entity.setExpiresAt(OffsetDateTime.now().plusMinutes(agentProperties.getConfirmationTtlMinutes()));
        // Set the actor explicitly: tool execution may run on a Reactor thread where the
        // request-scoped ThreadLocal is empty, so the audit meta-object handler would otherwise
        // record "anonymous" and the later ownership check on confirm would wrongly reject.
        if (StringUtils.hasText(actorUserId)) {
            entity.setCreatedBy(actorUserId);
            entity.setUpdatedBy(actorUserId);
        }
        agentPendingActionMapper.insert(entity);
        log.atInfo()
                .addKeyValue("confirmationId", entity.getId())
                .addKeyValue("conversationId", proposal.conversationId())
                .addKeyValue("toolName", proposal.toolName())
                .log("Agent pending action proposal created");
        return entity;
    }

    @Override
    public ChatConfirmResponse resolveConfirmation(String conversationId, String confirmationId, boolean approved) {
        AgentPendingActionEntity action = loadAction(conversationId, confirmationId);
        String currentUserId = CurrentUserContextHolder.currentOrAnonymous().userId();
        if (!currentUserId.equals(action.getCreatedBy())) {
            throw new BusinessException(
                    "AGENT_CONFIRM_FORBIDDEN",
                    "该待确认操作属于其他用户，无法处理。confirmationId=" + confirmationId);
        }
        if (!AgentPendingActionEntity.STATUS_PENDING.equals(action.getStatus())) {
            return new ChatConfirmResponse(confirmationId, action.getStatus(), false,
                    "该操作已处理过（当前状态 " + action.getStatus() + "），不能重复确认");
        }
        if (action.getExpiresAt() != null && OffsetDateTime.now().isAfter(action.getExpiresAt())) {
            action.setStatus(AgentPendingActionEntity.STATUS_EXPIRED);
            agentPendingActionMapper.updateById(action);
            return new ChatConfirmResponse(confirmationId, AgentPendingActionEntity.STATUS_EXPIRED, false,
                    "该操作提案已过期，请重新发起");
        }

        if (!approved) {
            if (!transitionPending(action, AgentPendingActionEntity.STATUS_REJECTED)) {
                return alreadyHandled(confirmationId);
            }
            log.atInfo()
                    .addKeyValue("confirmationId", confirmationId)
                    .addKeyValue("toolName", action.getToolName())
                    .log("Agent pending action rejected by user");
            agentMetrics.recordConfirmation("rejected");
            return new ChatConfirmResponse(confirmationId, AgentPendingActionEntity.STATUS_REJECTED, false,
                    "已取消，本次操作不会执行");
        }

        // Atomically claim the proposal before any side effect. Only one concurrent confirmer can
        // move PENDING -> APPROVED; all others observe an already-handled response.
        String executionOwner = UUID.randomUUID().toString();
        if (!claimPending(action, executionOwner)) {
            return alreadyHandled(confirmationId);
        }
        action.setStatus(AgentPendingActionEntity.STATUS_APPROVED);
        action.setExecutionOwner(executionOwner);
        action.setExecutionAttempts(action.getExecutionAttempts() == null ? 1 : action.getExecutionAttempts() + 1);
        action.setLeaseUntil(OffsetDateTime.now().plusMinutes(LEASE_MINUTES));
        try {
            String result = transactionTemplate.execute(status -> execute(action));
            action.setStatus(AgentPendingActionEntity.STATUS_EXECUTED);
            action.setExecutedAt(OffsetDateTime.now());
            action.setErrorMessage(null);
            action.setResultMessage(truncate(result, ERROR_MAX_LENGTH));
            action.setLeaseUntil(null);
            agentPendingActionMapper.updateById(action);
            log.atInfo()
                    .addKeyValue("confirmationId", confirmationId)
                    .addKeyValue("toolName", action.getToolName())
                    .log("Agent pending action executed");
            agentMetrics.recordConfirmation("approved_executed");
            return new ChatConfirmResponse(confirmationId, AgentPendingActionEntity.STATUS_EXECUTED, true, result);
        } catch (RuntimeException exception) {
            action.setStatus(AgentPendingActionEntity.STATUS_FAILED);
            action.setErrorMessage(truncate(exception.getMessage(), ERROR_MAX_LENGTH));
            action.setLeaseUntil(null);
            agentPendingActionMapper.updateById(action);
            log.atWarn()
                    .setCause(exception)
                    .addKeyValue("confirmationId", confirmationId)
                    .addKeyValue("toolName", action.getToolName())
                    .log("Agent pending action execution failed");
            agentMetrics.recordConfirmation("approved_failed");
            return new ChatConfirmResponse(confirmationId, AgentPendingActionEntity.STATUS_FAILED, false,
                    "执行失败：" + exception.getMessage());
        }
    }

    // ---------- execution dispatch ----------

    private String execute(AgentPendingActionEntity action) {
        Map<String, Object> args = readArguments(action);
        return switch (action.getToolName()) {
            case TOOL_SUBMIT_REIMBURSEMENT -> {
                Long id = longOf(args, "reimbursementId");
                ReimbursementDetailVO current = reimbursementService.getReimbursement(id);
                if (!"draft".equalsIgnoreCase(current.order().status())
                        && !"rejected".equalsIgnoreCase(current.order().status())) {
                    yield "报销单已对账，无需重复提交，单号 " + current.order().reimbursementNo()
                            + "，状态 " + current.order().status();
                }
                // Go through the approval orchestrator (not the raw service) so the group
                // notification and the private approver cards are pushed, exactly like the web UI.
                ReimbursementDetailVO vo = reimbursementApprovalService.submit(
                        id, new ReimbursementActionRequest(str(args, "reason")));
                yield "报销单已提交，单号 " + vo.order().reimbursementNo();
            }
            case TOOL_CREATE_REIMBURSEMENT -> {
                ReimbursementDetailVO created = reimbursementService.createReimbursement(
                        toCreateReimbursementRequest(args));
                boolean submitNow = Boolean.parseBoolean(String.valueOf(args.get("submitNow")));
                ReimbursementDetailVO vo = submitNow
                        ? reimbursementApprovalService.submitAfterCreate(created.order().id())
                        : created;
                yield "报销单已创建，单号 " + vo.order().reimbursementNo()
                        + "，状态 " + vo.order().status() + "，金额 " + vo.order().totalAmount();
            }
            case ACTION_EXECUTE_REIMBURSEMENT_PLAN -> {
                Long taskId = longOf(args, "taskId");
                var task = agentTaskService.execute(taskId);
                yield "任务 #" + task.id() + " 执行完成，状态 " + task.status()
                        + "，成功 " + task.completedSteps() + " 步，失败 " + task.failedSteps() + " 步";
            }
            default -> throw new BusinessException(
                    "AGENT_TOOL_UNKNOWN", "未知的写工具：" + action.getToolName());
        };
    }

    private ReimbursementCreateRequest toCreateReimbursementRequest(Map<String, Object> args) {
        // submitNow is orchestrated here (create then submitAfterCreate), never by the raw service,
        // so always pass false to the create request to avoid a double submit.
        return new ReimbursementCreateRequest(
                longOf(args, "projectId"),
                requireStr(args, "applicant"),
                str(args, "paymentType"),
                parseItems(args, "items"),
                str(args, "reason"),
                false);
    }

    @SuppressWarnings("unchecked")
    private static List<ReimbursementItemInput> parseItems(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new BusinessException("AGENT_ARGUMENT_MISSING", "缺少必要参数 " + key);
        }
        List<ReimbursementItemInput> items = new ArrayList<>();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> raw)) {
                throw new BusinessException("AGENT_ARGUMENT_INVALID", key + " 每一项必须是对象");
            }
            Map<String, Object> item = (Map<String, Object>) raw;
            items.add(new ReimbursementItemInput(
                    decimalOf(item, "amount"),
                    dateOf(item, "expenseDate"),
                    str(item, "vendor"),
                    str(item, "invoiceNo"),
                    str(item, "receiptFile"),
                    requireStr(item, "description"),
                    str(item, "counterpartyAccount")));
        }
        return items;
    }

    // ---------- helpers ----------

    private AgentPendingActionEntity loadAction(String conversationId, String confirmationId) {
        Long id;
        try {
            id = Long.valueOf(confirmationId.trim());
        } catch (NumberFormatException exception) {
            throw new BusinessException("AGENT_CONFIRM_ID_INVALID", "confirmationId 格式非法：" + confirmationId);
        }
        LambdaQueryWrapper<AgentPendingActionEntity> wrapper = new LambdaQueryWrapper<AgentPendingActionEntity>()
                .eq(AgentPendingActionEntity::getId, id)
                .eq(AgentPendingActionEntity::getConversationId, conversationId)
                .last("LIMIT 1");
        AgentPendingActionEntity action = agentPendingActionMapper.selectOne(wrapper);
        if (action == null) {
            throw new BusinessException(
                    "AGENT_CONFIRM_NOT_FOUND",
                    "未找到该待确认操作，可能已过期或不属于当前对话。confirmationId=" + confirmationId);
        }
        return action;
    }

    @Override
    public int recoverExpiredExecutions() {
        OffsetDateTime now = OffsetDateTime.now();
        List<AgentPendingActionEntity> expired = agentPendingActionMapper.selectList(
                new LambdaQueryWrapper<AgentPendingActionEntity>()
                        .eq(AgentPendingActionEntity::getStatus, AgentPendingActionEntity.STATUS_APPROVED)
                        .lt(AgentPendingActionEntity::getLeaseUntil, now)
                        .last("LIMIT 50"));
        int recovered = 0;
        for (AgentPendingActionEntity action : expired) {
            if (recoverOne(action, now)) {
                recovered++;
            }
        }
        return recovered;
    }

    private boolean recoverOne(AgentPendingActionEntity action, OffsetDateTime now) {
        String owner = UUID.randomUUID().toString();
        LambdaUpdateWrapper<AgentPendingActionEntity> claim = new LambdaUpdateWrapper<>();
        claim.eq(AgentPendingActionEntity::getId, action.getId())
                .eq(AgentPendingActionEntity::getStatus, AgentPendingActionEntity.STATUS_APPROVED)
                .lt(AgentPendingActionEntity::getLeaseUntil, now)
                .set(AgentPendingActionEntity::getExecutionOwner, owner)
                .set(AgentPendingActionEntity::getLeaseUntil, now.plusMinutes(LEASE_MINUTES))
                .setSql("execution_attempts = execution_attempts + 1")
                .set(AgentPendingActionEntity::getUpdatedAt, now);
        if (agentPendingActionMapper.update(null, claim) != 1) {
            return false;
        }
        CurrentUserTO previous = CurrentUserContextHolder.currentOrNull();
        try {
            CurrentUserContextHolder.set(CurrentUserTO.of(
                    action.getCreatedBy(), action.getActorUsername(), "default",
                    action.getConversationId(), parseRoles(action.getActorRoles())));
            String result = transactionTemplate.execute(status -> execute(action));
            LambdaUpdateWrapper<AgentPendingActionEntity> complete = new LambdaUpdateWrapper<>();
            complete.eq(AgentPendingActionEntity::getId, action.getId())
                    .eq(AgentPendingActionEntity::getExecutionOwner, owner)
                    .eq(AgentPendingActionEntity::getStatus, AgentPendingActionEntity.STATUS_APPROVED)
                    .set(AgentPendingActionEntity::getStatus, AgentPendingActionEntity.STATUS_EXECUTED)
                    .set(AgentPendingActionEntity::getResultMessage, truncate(result, ERROR_MAX_LENGTH))
                    .set(AgentPendingActionEntity::getExecutedAt, OffsetDateTime.now())
                    .set(AgentPendingActionEntity::getLeaseUntil, null)
                    .set(AgentPendingActionEntity::getErrorMessage, null);
            return agentPendingActionMapper.update(null, complete) == 1;
        } catch (RuntimeException exception) {
            LambdaUpdateWrapper<AgentPendingActionEntity> fail = new LambdaUpdateWrapper<>();
            fail.eq(AgentPendingActionEntity::getId, action.getId())
                    .eq(AgentPendingActionEntity::getExecutionOwner, owner)
                    .set(AgentPendingActionEntity::getStatus, AgentPendingActionEntity.STATUS_FAILED)
                    .set(AgentPendingActionEntity::getLeaseUntil, null)
                    .set(AgentPendingActionEntity::getErrorMessage, truncate(exception.getMessage(), ERROR_MAX_LENGTH));
            agentPendingActionMapper.update(null, fail);
            log.atWarn().setCause(exception).addKeyValue("confirmationId", action.getId())
                    .log("Expired Agent action recovery failed");
            return true;
        } finally {
            if (previous == null) {
                CurrentUserContextHolder.clear();
            } else {
                CurrentUserContextHolder.set(previous);
            }
        }
    }

    private static Set<String> parseRoles(String value) {
        if (!StringUtils.hasText(value)) {
            return Set.of("RESEARCHER");
        }
        return Set.of(value.split(","));
    }

    private boolean transitionPending(AgentPendingActionEntity action, String targetStatus) {
        LambdaUpdateWrapper<AgentPendingActionEntity> update = new LambdaUpdateWrapper<>();
        update.eq(AgentPendingActionEntity::getId, action.getId())
                .eq(AgentPendingActionEntity::getConversationId, action.getConversationId())
                .eq(AgentPendingActionEntity::getCreatedBy, action.getCreatedBy())
                .eq(AgentPendingActionEntity::getStatus, AgentPendingActionEntity.STATUS_PENDING)
                .set(AgentPendingActionEntity::getStatus, targetStatus)
                .set(AgentPendingActionEntity::getUpdatedAt, OffsetDateTime.now());
        return agentPendingActionMapper.update(null, update) == 1;
    }

    private boolean claimPending(AgentPendingActionEntity action, String executionOwner) {
        OffsetDateTime now = OffsetDateTime.now();
        LambdaUpdateWrapper<AgentPendingActionEntity> update = new LambdaUpdateWrapper<>();
        update.eq(AgentPendingActionEntity::getId, action.getId())
                .eq(AgentPendingActionEntity::getConversationId, action.getConversationId())
                .eq(AgentPendingActionEntity::getCreatedBy, action.getCreatedBy())
                .eq(AgentPendingActionEntity::getStatus, AgentPendingActionEntity.STATUS_PENDING)
                .set(AgentPendingActionEntity::getStatus, AgentPendingActionEntity.STATUS_APPROVED)
                .set(AgentPendingActionEntity::getExecutionOwner, executionOwner)
                .set(AgentPendingActionEntity::getLeaseUntil, now.plusMinutes(LEASE_MINUTES))
                .setSql("execution_attempts = execution_attempts + 1")
                .set(AgentPendingActionEntity::getUpdatedAt, now);
        return agentPendingActionMapper.update(null, update) == 1;
    }

    private ChatConfirmResponse alreadyHandled(String confirmationId) {
        AgentPendingActionEntity current = agentPendingActionMapper.selectById(Long.valueOf(confirmationId));
        String status = current == null ? "UNKNOWN" : current.getStatus();
        return new ChatConfirmResponse(confirmationId, status, false,
                "该操作已被处理或正在执行（当前状态 " + status + "），不会重复执行");
    }

    private Map<String, Object> readArguments(AgentPendingActionEntity action) {
        try {
            return objectMapper.readValue(action.getArgumentsJson(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            throw new BusinessException(
                    "AGENT_CONFIRM_ARGUMENTS_CORRUPT",
                    "待确认操作参数解析失败，confirmationId=" + action.getId());
        }
    }

    private String writeJson(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception exception) {
            throw new BusinessException("AGENT_PROPOSAL_SERIALIZE_FAILED", "提案参数序列化失败");
        }
    }

    private static String requireWithinLimit(String value, int maxLength, String code) {
        if (value != null && value.length() > maxLength) {
            throw new BusinessException(code, "待确认操作数据过大，请拆分后重试");
        }
        return value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "[TRUNCATED]";
    }

    private static Long longOf(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new BusinessException("AGENT_ARGUMENT_MISSING", "缺少必要参数 " + key);
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value).trim());
    }

    private static Integer intOf(Map<String, Object> args, String key) {
        return longOf(args, key).intValue();
    }

    private static BigDecimal decimalOf(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new BusinessException("AGENT_ARGUMENT_MISSING", "缺少必要参数 " + key);
        }
        return new BigDecimal(String.valueOf(value).trim());
    }

    private static LocalDate dateOf(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new BusinessException("AGENT_ARGUMENT_MISSING", "缺少必要参数 " + key);
        }
        return LocalDate.parse(String.valueOf(value).trim());
    }

    private static String str(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private static String requireStr(Map<String, Object> args, String key) {
        String text = str(args, key);
        if (text == null) {
            throw new BusinessException("AGENT_ARGUMENT_MISSING", "缺少必要参数 " + key);
        }
        return text;
    }
}
