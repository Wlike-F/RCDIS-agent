package com.rcdis.agent.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.agent.AgentToolContext;
import com.rcdis.agent.entity.AgentPendingActionEntity;
import com.rcdis.agent.service.AgentPendingActionService;
import com.rcdis.agent.to.AgentProposalTO;

import lombok.RequiredArgsConstructor;

/**
 * Shared plumbing for write tools: persist a proposal, push the {@code requires_confirmation} SSE
 * event, and hand the model a "nothing was executed yet" reply.
 *
 * <p>Centralised so every write tool follows the identical proposal/commit path and the
 * confirmation payload keys stay aligned with {@code frontend/src/stores/chat.ts}.</p>
 */
@Component
@RequiredArgsConstructor
public class ProposalSupport {

    private final AgentPendingActionService agentPendingActionService;
    private final ObjectMapper objectMapper;

    /**
     * Persists the proposal, emits the confirmation event, and returns the JSON string the tool
     * should return to the model.
     */
    public String propose(AgentToolContext context, AgentProposalTO proposal) {
        String actorUserId = context == null || context.currentUser() == null
                ? null
                : context.currentUser().userId();
        AgentPendingActionEntity entity = agentPendingActionService.createProposal(proposal, actorUserId);
        emitConfirmation(context, entity);

        Map<String, Object> llmReply = new LinkedHashMap<>();
        llmReply.put("ok", true);
        llmReply.put("pending", true);
        llmReply.put("confirmationId", String.valueOf(entity.getId()));
        llmReply.put("message", "已生成待确认提案，尚未执行任何变更。请向用户复述操作要点（对象、金额、日期、科目等），"
                + "并告知需在确认卡片中点击『确认执行』后才会真正写入；在用户确认前不要声称已完成。");
        return json(llmReply);
    }

    private void emitConfirmation(AgentToolContext context, AgentPendingActionEntity entity) {
        if (context == null || context.listener() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("confirmationId", String.valueOf(entity.getId()));
        payload.put("operation", entity.getToolName());
        payload.put("targetType", entity.getTargetType());
        payload.put("targetId", entity.getTargetId());
        payload.put("summary", entity.getSummary());
        payload.put("before", entity.getBeforeSnapshot());
        payload.put("after", entity.getAfterSnapshot());
        payload.put("reason", entity.getReason());
        payload.put("scope", entity.getScope());
        try {
            context.listener().onConfirmation(payload);
        } catch (RuntimeException ignored) {
            // SSE reporting must not break the tool / model call.
        }
    }

    /** Serializes a snapshot object; blank input becomes a readable placeholder. */
    public String snapshot(Object value) {
        if (value == null) {
            return "(新建，无既有记录)";
        }
        return json(value);
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{\"ok\":false,\"error\":\"序列化工具结果失败\"}";
        }
    }
}
