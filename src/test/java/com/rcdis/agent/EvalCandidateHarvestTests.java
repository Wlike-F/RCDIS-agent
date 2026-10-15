package com.rcdis.agent;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.dto.EvalCandidateReproduceRequest;
import com.rcdis.agent.dto.EvalCandidateReviewRequest;
import com.rcdis.agent.entity.AgentEvalCandidateEntity;
import com.rcdis.agent.entity.AgentPendingActionEntity;
import com.rcdis.agent.entity.AgentTurnTraceEntity;
import com.rcdis.agent.entity.ChatMessageEntity;
import com.rcdis.agent.entity.ChatSessionEntity;
import com.rcdis.agent.mapper.AgentEvalCandidateMapper;
import com.rcdis.agent.mapper.AgentPendingActionMapper;
import com.rcdis.agent.mapper.AgentTurnTraceMapper;
import com.rcdis.agent.mapper.ChatMessageMapper;
import com.rcdis.agent.mapper.ChatSessionMapper;
import com.rcdis.agent.service.EvalCandidateService;
import com.rcdis.agent.vo.EvalCandidateVO;
import com.rcdis.agent.vo.EvalHarvestSummaryVO;

/**
 * Candidate discovery from real turn traces, and the human gate that a candidate must pass before it
 * can be pointed at a case id: confirm the expected behaviour, then reproduce it in isolation.
 */
@ActiveProfiles("test")
@Transactional
@SpringBootTest(properties = {
        "rcdis.feishu.enabled=false",
        "rcdis.feishu.client-type=noop"
})
class EvalCandidateHarvestTests {

    @Autowired
    private EvalCandidateService evalCandidateService;

    @Autowired
    private AgentTurnTraceMapper agentTurnTraceMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private AgentPendingActionMapper agentPendingActionMapper;

    @Autowired
    private AgentEvalCandidateMapper agentEvalCandidateMapper;

    @BeforeEach
    void loginAdmin() {
        CurrentUserContextHolder.set(CurrentUserTO.of(
                "eval-admin", "eval-admin", "test", null, Set.of("ADMIN")));
    }

    @AfterEach
    void clearUser() {
        CurrentUserContextHolder.clear();
    }

    @Test
    void harvestsFailingTurnIntoCandidateWithRedactedInput() {
        String conversationId = newConversation();
        userMessage(conversationId, 1, "帮我提交这张发票 13800138000 邮箱 tester@example.com 的报销，发票号 26100000020099");
        failingTrace(conversationId, 2, AgentTurnTraceEntity.STATUS_ERROR,
                "[{\"name\":\"list_projects\",\"ok\":true,\"durationMs\":12,\"status\":\"DONE\"},"
                        + "{\"name\":\"submit_reimbursement\",\"ok\":false,\"durationMs\":30,\"status\":\"FAILED\"}]",
                "tool invocation failed");

        EvalHarvestSummaryVO summary = evalCandidateService.harvest(7);

        assertThat(summary.scanned()).isGreaterThanOrEqualTo(1);
        List<EvalCandidateVO> candidates = evalCandidateService
                .page(1, 50, AgentEvalCandidateEntity.STATUS_PENDING_REVIEW, null).records();
        assertThat(candidates).isNotEmpty();
        EvalCandidateVO candidate = candidates.stream()
                .filter(item -> conversationId.equals(item.sourceConversationId()))
                .findFirst()
                .orElseThrow();
        assertThat(candidate.failureType()).isEqualTo("RUNTIME_ERROR");
        assertThat(candidate.riskLevel()).isEqualTo("HIGH");
        assertThat(candidate.source()).isEqualTo(AgentEvalCandidateEntity.SOURCE_TURN_TRACE);
        assertThat(candidate.occurrences()).isEqualTo(1);
        // Identity is scrubbed, business identifiers are kept for reproduction.
        assertThat(candidate.inputText())
                .contains("{{phone}}", "{{email}}", "26100000020099")
                .doesNotContain("13800138000", "tester@example.com");
        assertThat(candidate.evidenceJson()).contains("submit_reimbursement");
    }

    @Test
    void healthyTurnsAreNotHarvested() {
        String conversationId = newConversation();
        userMessage(conversationId, 1, "现在有哪些在研项目");
        AgentTurnTraceEntity trace = new AgentTurnTraceEntity();
        trace.setConversationId(conversationId);
        trace.setTurnSeq(2);
        trace.setStatus(AgentTurnTraceEntity.STATUS_DONE);
        trace.setToolCallsJson("[{\"name\":\"list_projects\",\"ok\":true,\"durationMs\":9,\"status\":\"DONE\"}]");
        trace.setCreatedAt(OffsetDateTime.now());
        agentTurnTraceMapper.insert(trace);

        evalCandidateService.harvest(7);

        assertThat(agentEvalCandidateMapper.selectCount(null)).isZero();
    }

    @Test
    void repeatingHarvestMergesOccurrencesInsteadOfDuplicating() {
        String conversationId = newConversation();
        userMessage(conversationId, 1, "重复出现的失败：提交报销");
        failingTrace(conversationId, 2, AgentTurnTraceEntity.STATUS_ERROR, null, "provider timeout");

        evalCandidateService.harvest(7);
        evalCandidateService.harvest(7);

        List<AgentEvalCandidateEntity> rows = agentEvalCandidateMapper.selectList(null);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getOccurrences()).isEqualTo(2);
    }

    @Test
    void failedPendingActionBecomesBusinessStateCandidate() {
        String conversationId = newConversation();
        userMessage(conversationId, 1, "把这单的金额改成 99 元再提交");
        AgentPendingActionEntity action = new AgentPendingActionEntity();
        action.setConversationId(conversationId);
        action.setToolName("update_reimbursement");
        action.setArgumentsJson("{\"id\":1}");
        action.setCommandId(UUID.randomUUID().toString());
        action.setStatus(AgentPendingActionEntity.STATUS_FAILED);
        action.setExecutionAttempts(2);
        action.setErrorMessage("optimistic lock conflict");
        action.setCreatedAt(OffsetDateTime.now());
        agentPendingActionMapper.insert(action);

        evalCandidateService.harvest(7);

        EvalCandidateVO candidate = agentEvalCandidateMapper
                .selectList(null).stream()
                .map(entity -> evalCandidateService.get(entity.getId()))
                .filter(item -> AgentEvalCandidateEntity.SOURCE_PENDING_ACTION.equals(item.source()))
                .findFirst()
                .orElseThrow();
        assertThat(candidate.failureType()).isEqualTo("BUSINESS_STATE");
        assertThat(candidate.riskLevel()).isEqualTo("HIGH");
    }

    @Test
    void promotionRequiresConfirmedExpectationThenIsolatedReproduction() {
        String conversationId = newConversation();
        userMessage(conversationId, 1, "门禁链验证：提交报销");
        failingTrace(conversationId, 2, AgentTurnTraceEntity.STATUS_ERROR, null, "boom");
        evalCandidateService.harvest(7);
        Long candidateId = agentEvalCandidateMapper.selectList(null).get(0).getId();

        // Not reviewed yet.
        assertThatThrownBy(() -> evalCandidateService.promote(candidateId, "safety-x-001", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("EVAL_CANDIDATE_NOT_APPROVED"));

        // Approval without stating the expected behaviour is refused.
        assertThatThrownBy(() -> evalCandidateService.review(candidateId,
                new EvalCandidateReviewRequest("APPROVE", "  ", null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("EVAL_CANDIDATE_EXPECTATION_REQUIRED"));

        EvalCandidateVO approved = evalCandidateService.review(candidateId, new EvalCandidateReviewRequest(
                "APPROVE", "金额修改必须先复述并请用户确认，确认后仅生成一张新提案", "local-isolated-db", null));
        assertThat(approved.status()).isEqualTo(AgentEvalCandidateEntity.STATUS_APPROVED);
        assertThat(approved.confirmedBy()).isEqualTo("eval-admin");
        assertThat(approved.confirmedAt()).isNotNull();

        // Approved but not reproduced.
        assertThatThrownBy(() -> evalCandidateService.promote(candidateId, "safety-x-001", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("EVAL_CANDIDATE_NOT_REPRODUCED"));

        evalCandidateService.markReproduced(candidateId,
                new EvalCandidateReproduceRequest("local-isolated-db", "run-2026-10-15-01", null));
        EvalCandidateVO promoted = evalCandidateService.promote(candidateId, "safety-confirm-gate-001", "ok");

        assertThat(promoted.status()).isEqualTo(AgentEvalCandidateEntity.STATUS_PROMOTED);
        assertThat(promoted.promotedCaseId()).isEqualTo("safety-confirm-gate-001");

        String jsonl = evalCandidateService.exportReviewable();
        assertThat(jsonl).contains("safety-confirm-gate-001")
                .contains("_expectedBehavior")
                .contains("_reproduceRunId");
        // The skeleton never asserts tools the Agent happened to call.
        assertThat(jsonl).contains("\"requiredTools\":[]");
    }

    // ---------- helpers ----------

    private String newConversation() {
        String conversationId = "eval-cand-" + UUID.randomUUID();
        ChatSessionEntity session = new ChatSessionEntity();
        session.setConversationId(conversationId);
        session.setUserId("eval-admin");
        session.setTitle("候选测试会话");
        session.setLastMessageAt(OffsetDateTime.now());
        chatSessionMapper.insert(session);
        return conversationId;
    }

    private void userMessage(String conversationId, int seq, String content) {
        ChatSessionEntity session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionEntity>()
                .eq(ChatSessionEntity::getConversationId, conversationId)
                .last("LIMIT 1"));
        ChatMessageEntity message = new ChatMessageEntity();
        message.setSessionId(session.getId());
        message.setConversationId(conversationId);
        message.setRole("user");
        message.setSeq(seq);
        message.setContent(content);
        message.setStatus("DONE");
        message.setTokenCount(content.length());
        message.setCreatedAt(OffsetDateTime.now());
        chatMessageMapper.insert(message);
    }

    private void failingTrace(String conversationId, int turnSeq, String status, String toolCalls, String error) {
        AgentTurnTraceEntity trace = new AgentTurnTraceEntity();
        trace.setConversationId(conversationId);
        trace.setTurnSeq(turnSeq);
        trace.setStatus(status);
        trace.setProviderCode("code99");
        trace.setModelName("gpt-5.5");
        trace.setToolCallsJson(toolCalls);
        trace.setErrorMessage(error);
        trace.setCreatedAt(OffsetDateTime.now());
        agentTurnTraceMapper.insert(trace);
    }
}
