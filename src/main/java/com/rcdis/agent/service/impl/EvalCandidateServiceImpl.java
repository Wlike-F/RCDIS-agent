package com.rcdis.agent.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.dto.EvalCandidateReproduceRequest;
import com.rcdis.agent.dto.EvalCandidateReviewRequest;
import com.rcdis.agent.entity.AgentEvalCandidateEntity;
import com.rcdis.agent.entity.AgentPendingActionEntity;
import com.rcdis.agent.entity.AgentTurnTraceEntity;
import com.rcdis.agent.entity.ChatMessageEntity;
import com.rcdis.agent.mapper.AgentEvalCandidateMapper;
import com.rcdis.agent.mapper.AgentPendingActionMapper;
import com.rcdis.agent.mapper.AgentTurnTraceMapper;
import com.rcdis.agent.mapper.ChatMessageMapper;
import com.rcdis.agent.service.EvalCandidateService;
import com.rcdis.agent.vo.EvalCandidateVO;
import com.rcdis.agent.vo.EvalHarvestSummaryVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Failure discovery over persisted turn traces, with a human gate before anything reaches the
 * evaluation dataset.
 *
 * <p>Signals come from what the runtime already recorded (turn status, halt reason, failed tool
 * results, failed pending actions). Identity data is scrubbed on the way in, and every candidate
 * keeps its evidence plus the review trail (who confirmed the expectation, where it was reproduced)
 * so a future reviewer can judge it without digging through production data.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalCandidateServiceImpl implements EvalCandidateService {

    /** Failure buckets; mirrors the evaluation classifier plus a business-state bucket. */
    private static final String FAILURE_RUNTIME = "RUNTIME_ERROR";
    private static final String FAILURE_STALL = "STALL_LOOP";
    private static final String FAILURE_NO_GRACEFUL_EXIT = "NO_GRACEFUL_EXIT";
    private static final String FAILURE_TOOL_ERROR = "TOOL_ERROR";
    private static final String FAILURE_BUSINESS_STATE = "BUSINESS_STATE";
    private static final String FAILURE_UNKNOWN = "UNKNOWN";

    private static final Set<String> WRITE_TOOL_HINTS = Set.of(
            "create_reimbursement", "submit_reimbursement", "update_reimbursement",
            "void_reimbursement", "plan_reimbursement_submissions", "retry_reimbursement_plan");

    private static final int MAX_TRACE_IDS_KEPT = 20;
    private static final int INPUT_MAX_LENGTH = 1_800;
    private static final int EVIDENCE_MAX_LENGTH = 3_800;

    private final AgentTurnTraceMapper agentTurnTraceMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final AgentPendingActionMapper agentPendingActionMapper;
    private final AgentEvalCandidateMapper agentEvalCandidateMapper;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;

    // ---------- harvesting ----------

    @Override
    @Transactional
    public EvalHarvestSummaryVO harvest(int windowDays) {
        int days = Math.max(1, Math.min(windowDays, 90));
        int cap = Math.max(50, agentProperties.getEvalCandidates().getMaxTraces());
        OffsetDateTime since = OffsetDateTime.now().minusDays(days);

        List<AgentTurnTraceEntity> traces = agentTurnTraceMapper.selectList(
                new LambdaQueryWrapper<AgentTurnTraceEntity>()
                        .ge(AgentTurnTraceEntity::getCreatedAt, since)
                        .orderByDesc(AgentTurnTraceEntity::getId)
                        .last("LIMIT " + cap));

        int scanned = 0;
        int created = 0;
        int merged = 0;
        int skippedNoInput = 0;
        for (AgentTurnTraceEntity trace : traces) {
            FailureSignal signal = signalOf(trace);
            if (signal == null) {
                continue;
            }
            scanned++;
            String input = attributableInput(trace.getConversationId(), trace.getTurnSeq());
            if (!StringUtils.hasText(input)) {
                skippedNoInput++;
                continue;
            }
            UpsertResult result = upsert(AgentEvalCandidateEntity.SOURCE_TURN_TRACE,
                    signal.failureType(), redact(input), signal.tools(), signal.evidence(),
                    trace.getConversationId(), String.valueOf(trace.getId()), trace.getCreatedAt());
            if (result.created()) {
                created++;
            } else {
                merged++;
            }
        }

        // Confirmed proposals whose execution failed are business-state failures, not model noise.
        List<AgentPendingActionEntity> failedActions = agentPendingActionMapper.selectList(
                new LambdaQueryWrapper<AgentPendingActionEntity>()
                        .ge(AgentPendingActionEntity::getCreatedAt, since)
                        .eq(AgentPendingActionEntity::getStatus, AgentPendingActionEntity.STATUS_FAILED)
                        .orderByDesc(AgentPendingActionEntity::getId)
                        .last("LIMIT " + cap));
        for (AgentPendingActionEntity action : failedActions) {
            String input = attributableInput(action.getConversationId(), null);
            if (!StringUtils.hasText(input)) {
                skippedNoInput++;
                continue;
            }
            scanned++;
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("pendingActionId", action.getId());
            evidence.put("toolName", action.getToolName());
            evidence.put("errorMessage", truncate(action.getErrorMessage(), 400));
            evidence.put("attempts", action.getExecutionAttempts());
            UpsertResult result = upsert(AgentEvalCandidateEntity.SOURCE_PENDING_ACTION,
                    FAILURE_BUSINESS_STATE, redact(input),
                    List.of(String.valueOf(action.getToolName())), toJson(evidence),
                    action.getConversationId(), "action-" + action.getId(), action.getCreatedAt());
            if (result.created()) {
                created++;
            } else {
                merged++;
            }
        }

        log.atInfo()
                .addKeyValue("windowDays", days)
                .addKeyValue("scanned", scanned)
                .addKeyValue("created", created)
                .addKeyValue("merged", merged)
                .addKeyValue("skippedNoInput", skippedNoInput)
                .log("Agent eval candidates harvested");
        return new EvalHarvestSummaryVO(days, scanned, created, merged, skippedNoInput);
    }

    /** Classifies one trace into a failure signal, or null when the turn looks healthy. */
    private FailureSignal signalOf(AgentTurnTraceEntity trace) {
        List<String> tools = new ArrayList<>();
        List<String> failedTools = new ArrayList<>();
        parseToolCalls(trace.getToolCallsJson(), tools, failedTools);

        String status = trace.getStatus() == null ? "" : trace.getStatus().toUpperCase(Locale.ROOT);
        String failureType;
        if (AgentTurnTraceEntity.STATUS_ERROR.equals(status) || AgentTurnTraceEntity.STATUS_TIMEOUT.equals(status)) {
            failureType = FAILURE_RUNTIME;
        } else if (StringUtils.hasText(trace.getHaltReason())) {
            String reason = trace.getHaltReason().toLowerCase(Locale.ROOT);
            failureType = reason.contains("stall") || reason.contains("repeat")
                    ? FAILURE_STALL : FAILURE_NO_GRACEFUL_EXIT;
        } else if (!failedTools.isEmpty()) {
            failureType = FAILURE_TOOL_ERROR;
        } else {
            return null;
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("traceId", trace.getId());
        evidence.put("turnSeq", trace.getTurnSeq());
        evidence.put("traceStatus", trace.getStatus());
        evidence.put("haltReason", trace.getHaltReason());
        evidence.put("haltCount", trace.getHaltCount());
        evidence.put("frozenTools", trace.getFrozenToolsJson());
        evidence.put("failedTools", failedTools);
        evidence.put("errorMessage", truncate(trace.getErrorMessage(), 300));
        evidence.put("providerCode", trace.getProviderCode());
        evidence.put("modelName", trace.getModelName());
        return new FailureSignal(failureType, List.copyOf(new LinkedHashSet<>(tools)), toJson(evidence));
    }

    private void parseToolCalls(String json, List<String> tools, List<String> failedTools) {
        if (!StringUtils.hasText(json)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return;
            }
            root.forEach(node -> {
                String name = node.path("name").asText(null);
                if (!StringUtils.hasText(name)) {
                    return;
                }
                tools.add(name);
                boolean ok = !node.has("ok") || node.path("ok").asBoolean(true);
                if (!ok) {
                    failedTools.add(name);
                }
            });
        } catch (Exception exception) {
            log.atDebug().addKeyValue("reason", "unparsable toolCallsJson").log("Eval candidate parse skipped");
        }
    }

    /** The user utterance that triggered the turn, if it can still be attributed. */
    private String attributableInput(String conversationId, Integer turnSeq) {
        if (!StringUtils.hasText(conversationId)) {
            return null;
        }
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getConversationId, conversationId)
                .eq(ChatMessageEntity::getRole, "user");
        if (turnSeq != null) {
            wrapper.le(ChatMessageEntity::getSeq, turnSeq);
        }
        wrapper.orderByDesc(ChatMessageEntity::getSeq).last("LIMIT 1");
        ChatMessageEntity message = chatMessageMapper.selectOne(wrapper);
        return message == null ? null : message.getContent();
    }

    private UpsertResult upsert(String source, String failureType, String input, List<String> tools,
                                String evidence, String conversationId, String traceRef, OffsetDateTime seenAt) {
        String fingerprint = fingerprint(failureType, input, tools);
        AgentEvalCandidateEntity existing = agentEvalCandidateMapper.selectOne(
                new LambdaQueryWrapper<AgentEvalCandidateEntity>()
                        .eq(AgentEvalCandidateEntity::getFingerprint, fingerprint)
                        .last("LIMIT 1"));
        if (existing != null) {
            existing.setOccurrences(existing.getOccurrences() == null ? 2 : existing.getOccurrences() + 1);
            existing.setLastSeenAt(seenAt == null ? OffsetDateTime.now() : seenAt);
            existing.setSourceTraceIds(appendTraceIds(existing.getSourceTraceIds(), traceRef));
            agentEvalCandidateMapper.updateById(existing);
            return new UpsertResult(false);
        }

        AgentEvalCandidateEntity entity = new AgentEvalCandidateEntity();
        entity.setFingerprint(fingerprint);
        entity.setSource(source);
        entity.setFailureType(failureType);
        entity.setCategoryGuess(categoryGuess(failureType, tools));
        entity.setRiskLevel(riskLevel(failureType, tools));
        entity.setInputText(truncate(input, INPUT_MAX_LENGTH));
        entity.setObservedToolsJson(toJson(tools));
        entity.setEvidenceJson(truncate(evidence, EVIDENCE_MAX_LENGTH));
        entity.setSourceConversationId(conversationId);
        entity.setSourceTraceIds(truncate(traceRef, 500));
        entity.setOccurrences(1);
        entity.setFirstSeenAt(seenAt == null ? OffsetDateTime.now() : seenAt);
        entity.setLastSeenAt(seenAt == null ? OffsetDateTime.now() : seenAt);
        entity.setStatus(AgentEvalCandidateEntity.STATUS_PENDING_REVIEW);
        agentEvalCandidateMapper.insert(entity);
        return new UpsertResult(true);
    }

    // ---------- queries and the review gate ----------

    @Override
    public PageResponse<EvalCandidateVO> page(long current, long size, String status, String failureType) {
        if (current < 1) {
            throw new BusinessException("PAGE_CURRENT_INVALID", "current 必须大于 0", HttpStatus.BAD_REQUEST);
        }
        if (size < 1 || size > 500) {
            throw new BusinessException("PAGE_SIZE_INVALID", "size 必须在 1 到 500 之间", HttpStatus.BAD_REQUEST);
        }
        Page<AgentEvalCandidateEntity> page = agentEvalCandidateMapper.selectPage(
                new Page<>(current, size),
                new LambdaQueryWrapper<AgentEvalCandidateEntity>()
                        .eq(StringUtils.hasText(status), AgentEvalCandidateEntity::getStatus, status)
                        .eq(StringUtils.hasText(failureType), AgentEvalCandidateEntity::getFailureType, failureType)
                        .orderByDesc(AgentEvalCandidateEntity::getOccurrences)
                        .orderByDesc(AgentEvalCandidateEntity::getLastSeenAt));
        List<EvalCandidateVO> records = page.getRecords().stream().map(this::toVO).toList();
        Page<EvalCandidateVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(records);
        return PageResponse.fromPage(voPage);
    }

    @Override
    public EvalCandidateVO get(Long id) {
        return toVO(findEntity(id));
    }

    @Override
    @Transactional
    public EvalCandidateVO review(Long id, EvalCandidateReviewRequest request) {
        AgentEvalCandidateEntity entity = findEntity(id);
        CurrentUserTO user = CurrentUserContextHolder.currentOrAnonymous();
        if ("REJECT".equalsIgnoreCase(request.decision())) {
            entity.setStatus(AgentEvalCandidateEntity.STATUS_REJECTED);
            entity.setPromoteNote(truncate(request.note(), 500));
            agentEvalCandidateMapper.updateById(entity);
            log.atInfo().addKeyValue("candidateId", id).log("Eval candidate rejected");
            return toVO(entity);
        }
        if (!StringUtils.hasText(request.expectedBehavior())) {
            throw new BusinessException(
                    "EVAL_CANDIDATE_EXPECTATION_REQUIRED",
                    "通过候选前必须写明预期行为（expectedBehavior），否则用例只是在固化模型的偶然行为",
                    HttpStatus.BAD_REQUEST);
        }
        entity.setStatus(AgentEvalCandidateEntity.STATUS_APPROVED);
        entity.setExpectedBehavior(truncate(request.expectedBehavior(), 2000));
        // The stable reviewer identity is the login name; username() carries the display name here.
        entity.setConfirmedBy(user.userId());
        entity.setConfirmedAt(OffsetDateTime.now());
        if (StringUtils.hasText(request.reproduceEnv())) {
            entity.setReproduceEnv(truncate(request.reproduceEnv(), 128));
        }
        if (StringUtils.hasText(request.note())) {
            entity.setPromoteNote(truncate(request.note(), 500));
        }
        agentEvalCandidateMapper.updateById(entity);
        log.atInfo()
                .addKeyValue("candidateId", id)
                .addKeyValue("confirmedBy", user.username())
                .log("Eval candidate approved");
        return toVO(entity);
    }

    @Override
    @Transactional
    public EvalCandidateVO markReproduced(Long id, EvalCandidateReproduceRequest request) {
        AgentEvalCandidateEntity entity = findEntity(id);
        if (!AgentEvalCandidateEntity.STATUS_APPROVED.equals(entity.getStatus())) {
            throw new BusinessException(
                    "EVAL_CANDIDATE_NOT_APPROVED",
                    "只有已确认预期行为的候选才能登记复现结果。candidateId=" + id,
                    HttpStatus.CONFLICT);
        }
        entity.setReproduceEnv(truncate(request.reproduceEnv(), 128));
        entity.setReproduceRunId(truncate(request.reproduceRunId(), 64));
        entity.setReproducedAt(OffsetDateTime.now());
        if (StringUtils.hasText(request.note())) {
            entity.setPromoteNote(truncate(request.note(), 500));
        }
        agentEvalCandidateMapper.updateById(entity);
        log.atInfo()
                .addKeyValue("candidateId", id)
                .addKeyValue("reproduceEnv", entity.getReproduceEnv())
                .log("Eval candidate reproduced");
        return toVO(entity);
    }

    @Override
    @Transactional
    public EvalCandidateVO promote(Long id, String promotedCaseId, String note) {
        AgentEvalCandidateEntity entity = findEntity(id);
        if (!AgentEvalCandidateEntity.STATUS_APPROVED.equals(entity.getStatus())) {
            throw new BusinessException(
                    "EVAL_CANDIDATE_NOT_APPROVED",
                    "候选尚未确认预期行为，不能入库。candidateId=" + id,
                    HttpStatus.CONFLICT);
        }
        if (entity.getReproducedAt() == null || !StringUtils.hasText(entity.getReproduceEnv())) {
            throw new BusinessException(
                    "EVAL_CANDIDATE_NOT_REPRODUCED",
                    "候选必须先在隔离环境复现（登记 reproduceEnv 与复现时间）才能入库。candidateId=" + id,
                    HttpStatus.CONFLICT);
        }
        if (!StringUtils.hasText(promotedCaseId)) {
            throw new BusinessException(
                    "EVAL_CANDIDATE_CASE_ID_REQUIRED",
                    "入库必须提供写入 cases.jsonl 的用例 id",
                    HttpStatus.BAD_REQUEST);
        }
        entity.setStatus(AgentEvalCandidateEntity.STATUS_PROMOTED);
        entity.setPromotedCaseId(truncate(promotedCaseId.trim(), 64));
        if (StringUtils.hasText(note)) {
            entity.setPromoteNote(truncate(note, 500));
        }
        agentEvalCandidateMapper.updateById(entity);
        log.atInfo()
                .addKeyValue("candidateId", id)
                .addKeyValue("promotedCaseId", entity.getPromotedCaseId())
                .log("Eval candidate promoted into the dataset");
        return toVO(entity);
    }

    @Override
    public String exportReviewable() {
        List<AgentEvalCandidateEntity> ready = agentEvalCandidateMapper.selectList(
                new LambdaQueryWrapper<AgentEvalCandidateEntity>()
                        .in(AgentEvalCandidateEntity::getStatus,
                                AgentEvalCandidateEntity.STATUS_APPROVED,
                                AgentEvalCandidateEntity.STATUS_PROMOTED)
                        .isNotNull(AgentEvalCandidateEntity::getExpectedBehavior)
                        .orderByDesc(AgentEvalCandidateEntity::getOccurrences));
        StringBuilder jsonl = new StringBuilder();
        for (AgentEvalCandidateEntity entity : ready) {
            Map<String, Object> skeleton = new LinkedHashMap<>();
            skeleton.put("id", StringUtils.hasText(entity.getPromotedCaseId())
                    ? entity.getPromotedCaseId() : "candidate-" + entity.getId());
            skeleton.put("category", entity.getCategoryGuess());
            skeleton.put("riskLevel", entity.getRiskLevel());
            skeleton.put("input", entity.getInputText());
            // Assertions stay empty on purpose: what the Agent did online is evidence, not a spec.
            skeleton.put("requiredTools", List.of());
            skeleton.put("forbiddenTools", List.of());
            skeleton.put("confirmation", "OPTIONAL");
            skeleton.put("_candidateId", entity.getId());
            skeleton.put("_failureType", entity.getFailureType());
            skeleton.put("_expectedBehavior", entity.getExpectedBehavior());
            skeleton.put("_occurrences", entity.getOccurrences());
            skeleton.put("_reproduceEnv", entity.getReproduceEnv());
            skeleton.put("_reproduceRunId", entity.getReproduceRunId());
            skeleton.put("_sourceTraceIds", entity.getSourceTraceIds());
            jsonl.append(toJson(skeleton)).append('\n');
        }
        return jsonl.toString();
    }

    // ---------- helpers ----------

    private AgentEvalCandidateEntity findEntity(Long id) {
        AgentEvalCandidateEntity entity = agentEvalCandidateMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(
                    "EVAL_CANDIDATE_NOT_FOUND", "评估候选不存在。candidateId=" + id, HttpStatus.NOT_FOUND);
        }
        return entity;
    }

    private EvalCandidateVO toVO(AgentEvalCandidateEntity entity) {
        return new EvalCandidateVO(
                entity.getId(), entity.getFingerprint(), entity.getSource(), entity.getFailureType(),
                entity.getCategoryGuess(), entity.getRiskLevel(), entity.getInputText(),
                entity.getObservedToolsJson(), entity.getEvidenceJson(), entity.getSourceConversationId(),
                entity.getSourceTraceIds(), entity.getOccurrences(), entity.getFirstSeenAt(),
                entity.getLastSeenAt(), entity.getStatus(), entity.getExpectedBehavior(),
                entity.getConfirmedBy(), entity.getConfirmedAt(), entity.getReproduceEnv(),
                entity.getReproducedAt(), entity.getReproduceRunId(), entity.getPromoteNote(),
                entity.getPromotedCaseId());
    }

    private String categoryGuess(String failureType, List<String> tools) {
        if (FAILURE_BUSINESS_STATE.equals(failureType)) {
            return "CONFIRMATION_SAFETY";
        }
        if (FAILURE_STALL.equals(failureType) || FAILURE_NO_GRACEFUL_EXIT.equals(failureType)) {
            return "GRACEFUL_EXIT";
        }
        if (FAILURE_RUNTIME.equals(failureType) || FAILURE_TOOL_ERROR.equals(failureType)) {
            return "TOOL_SELECTION";
        }
        return "SCOPE_SAFETY";
    }

    private String riskLevel(String failureType, List<String> tools) {
        boolean touchesMoney = tools.stream().anyMatch(WRITE_TOOL_HINTS::contains);
        if (touchesMoney || FAILURE_BUSINESS_STATE.equals(failureType)) {
            return "HIGH";
        }
        return FAILURE_UNKNOWN.equals(failureType) ? "LOW" : "MEDIUM";
    }

    /**
     * Scrubs identity data before anything is stored as a candidate. Business identifiers (project
     * codes, invoice numbers, amounts) are kept because a case cannot be reproduced without them;
     * candidates stay admin-only and are scrubbed again by the reviewer before committing a case.
     */
    static String redact(String input) {
        if (input == null) {
            return null;
        }
        String redacted = input
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "{{email}}")
                .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "{{phone}}")
                .replaceAll("(?i)\\b\\d{17}[0-9xX]\\b", "{{idcard}}");
        return redacted.trim();
    }

    private static String fingerprint(String failureType, String input, List<String> tools) {
        Set<String> sortedTools = new TreeSet<>(tools == null ? List.of() : tools);
        String raw = failureType + "|" + input.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")
                + "|" + String.join(",", sortedTools);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String appendTraceIds(String existing, String addition) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (StringUtils.hasText(existing)) {
            for (String part : existing.split(",")) {
                if (StringUtils.hasText(part)) {
                    ids.add(part.trim());
                }
            }
        }
        ids.add(addition);
        while (ids.size() > MAX_TRACE_IDS_KEPT) {
            ids.remove(ids.iterator().next());
        }
        return String.join(",", ids);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "[]";
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record FailureSignal(String failureType, List<String> tools, String evidence) {
    }

    private record UpsertResult(boolean created) {
    }
}
