package com.rcdis.agent.service.impl;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.agent.TurnTraceCollector;
import com.rcdis.agent.entity.AgentTurnTraceEntity;
import com.rcdis.agent.mapper.AgentTurnTraceMapper;
import com.rcdis.agent.service.AgentTraceService;
import com.rcdis.agent.to.ToolCallTraceTO;
import com.rcdis.agent.vo.AgentTurnTraceVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTraceServiceImpl implements AgentTraceService {

    private final AgentTurnTraceMapper agentTurnTraceMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void record(TurnTraceCollector collector,
                       String conversationId,
                       String providerCode,
                       String modelName,
                       String status,
                       String errorMessage) {
        if (collector == null || !StringUtils.hasText(conversationId)) {
            return;
        }
        try {
            collector.finish();
            AgentTurnTraceEntity entity = new AgentTurnTraceEntity();
            entity.setConversationId(conversationId);
            entity.setTurnSeq(nextSeq(conversationId));
            entity.setProviderCode(providerCode);
            entity.setModelName(modelName);
            entity.setStatus(status);
            entity.setPromptTokens(collector.promptTokens());
            entity.setCompletionTokens(collector.completionTokens());
            entity.setTotalTokens(collector.totalTokens());
            entity.setFirstTokenMs(collector.firstTokenMs());
            entity.setTotalMs(collector.totalMs());
            entity.setToolCallsJson(writeToolCalls(collector.toolCalls()));
            entity.setErrorMessage(truncate(errorMessage));
            entity.setCreatedAt(OffsetDateTime.now());
            agentTurnTraceMapper.insert(entity);
        } catch (RuntimeException exception) {
            // Observability must never break the chat turn.
            log.atWarn()
                    .setCause(exception)
                    .addKeyValue("conversationId", conversationId)
                    .log("Failed to persist agent turn trace");
        }
    }

    @Override
    public List<AgentTurnTraceVO> listByConversation(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return List.of();
        }
        LambdaQueryWrapper<AgentTurnTraceEntity> wrapper = new LambdaQueryWrapper<AgentTurnTraceEntity>()
                .eq(AgentTurnTraceEntity::getConversationId, conversationId)
                .orderByAsc(AgentTurnTraceEntity::getId);
        List<AgentTurnTraceEntity> rows = agentTurnTraceMapper.selectList(wrapper);
        List<AgentTurnTraceVO> result = new ArrayList<>(rows.size());
        for (AgentTurnTraceEntity row : rows) {
            result.add(toVO(row));
        }
        return result;
    }

    // ---------- helpers ----------

    private int nextSeq(String conversationId) {
        LambdaQueryWrapper<AgentTurnTraceEntity> wrapper = new LambdaQueryWrapper<AgentTurnTraceEntity>()
                .eq(AgentTurnTraceEntity::getConversationId, conversationId);
        Long count = agentTurnTraceMapper.selectCount(wrapper);
        return (count == null ? 0 : count.intValue()) + 1;
    }

    private String writeToolCalls(List<ToolCallTraceTO> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(toolCalls);
        } catch (Exception exception) {
            return null;
        }
    }

    private List<ToolCallTraceTO> readToolCalls(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ToolCallTraceTO.class));
        } catch (Exception exception) {
            return List.of();
        }
    }

    private AgentTurnTraceVO toVO(AgentTurnTraceEntity row) {
        return new AgentTurnTraceVO(
                row.getId(),
                row.getConversationId(),
                row.getTurnSeq(),
                row.getProviderCode(),
                row.getModelName(),
                row.getStatus(),
                row.getPromptTokens(),
                row.getCompletionTokens(),
                row.getTotalTokens(),
                row.getFirstTokenMs(),
                row.getTotalMs(),
                readToolCalls(row.getToolCallsJson()),
                row.getErrorMessage(),
                row.getCreatedAt());
    }

    private static String truncate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String singleLine = value.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 1000 ? singleLine : singleLine.substring(0, 1000);
    }
}
