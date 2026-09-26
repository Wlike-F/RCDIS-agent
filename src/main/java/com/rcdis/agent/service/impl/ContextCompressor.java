package com.rcdis.agent.service.impl;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.agent.ConversationLocks;
import com.rcdis.agent.common.util.TokenEstimator;
import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.entity.ChatMessageEntity;
import com.rcdis.agent.entity.ChatSessionEntity;
import com.rcdis.agent.infrastructure.ai.ChatModelFactory;
import com.rcdis.agent.service.ChatHistoryService;
import com.rcdis.agent.service.ModelProviderService;
import com.rcdis.agent.vo.AgentMemoryVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Incremental context compressor (rolling summary + extractive hard-facts).
 *
 * <p>Runs asynchronously after a turn. It derives
 * {@code rolling_summary} / {@code summary_facts} / {@code summary_upto_seq} on the session so the
 * prompt window can drop old raw turns; the persistence policy later redacts content outside its
 * bounded raw retention window. On any failure it degrades to
 * "no compression, window truncation only" and logs a warning (never silently).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextCompressor {

    private static final String COMPRESS_PROMPT = """
            你是上下文压缩器。输入为一段对话历史（每行带 [seq] 序号）与既有摘要/要点。
            历史中的 role 有三种：user（用户）、assistant（你的回复）、tool（工具真实返回值，已核验）。
            tool 行是权威数据来源，其结论优先于 assistant 的转述；两者冲突时以 tool 行为准。
            任务：
            1) 从历史中挑选硬事实片段：凡涉及 金额/项目编号/报销单号/支出id/日期/决定/待办/工具查询结论/用户偏好与约束 的句子，\
            原样截取（不得改写数字、不得省略单位、不得编造），输出为 facts 数组，元素形如 \
            {"type":"amount|order|project|decision|todo|tool_result|user","text":"<原文片段>","turnSeq":<seq>,"key":"<归一化键>"}。
            2) 写一段简短叙述 narrative（<=200字），概括这段历史的目标、进展与未决事项；若提供了既有摘要，请与之合并。
            约束：
            - 数字、单号、日期必须与原文完全一致。
            - 不得引入历史中不存在的信息。
            - 只输出一个 JSON 对象：{"facts":[...],"narrative":"..."}，不要输出任何其他文字。
            """;

    private final ChatHistoryService chatHistoryService;
    private final ModelProviderService modelProviderService;
    private final ChatModelFactory chatModelFactory;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final ConversationLocks conversationLocks;

    @Async("memoryTaskExecutor")
    public void compressIfNeeded(String conversationId) {
        AgentProperties.Memory mem = agentProperties.getMemory();
        if (!mem.isCompressEnabled() || !StringUtils.hasText(conversationId)) {
            return;
        }
        // Never interleave with an in-flight turn for the same conversation.
        conversationLocks.runLocked(conversationId, () -> compressLocked(conversationId, mem));
    }

    private void compressLocked(String conversationId, AgentProperties.Memory mem) {
        try {
            ChatSessionEntity session = chatHistoryService.peekSession(conversationId);
            if (session == null) {
                return;
            }
            int total = session.getMessageCount() == null ? 0 : session.getMessageCount();
            int upto = session.getSummaryUptoSeq() == null ? 0 : session.getSummaryUptoSeq();

            boolean countTrigger = total > mem.getCompressThreshold();
            boolean tokenTrigger = false;
            if (!countTrigger) {
                tokenTrigger = uncompressedTokens(conversationId, upto, total) > mem.getWindowTokens();
            }
            if (!countTrigger && !tokenTrigger) {
                return;
            }
            int targetBoundary = total - mem.getWindowMessages();
            if (targetBoundary <= upto) {
                return;
            }

            int count = session.getCompressCount() == null ? 0 : session.getCompressCount();
            boolean calibrate = mem.getCalibrateEvery() > 0 && (count + 1) % mem.getCalibrateEvery() == 0;
            int fromSeq = calibrate ? 1 : upto + 1;
            // Includes role=tool rows: a verified tool conclusion is the most valuable fact source and
            // must not vanish just because its raw turn left the model window.
            List<ChatMessageEntity> batch =
                    chatHistoryService.readTurnsWithToolResults(conversationId, fromSeq, targetBoundary);
            if (batch.isEmpty()) {
                return;
            }

            String existingSummary = calibrate ? null : session.getRollingSummary();
            String existingFacts = calibrate ? null : session.getSummaryFacts();
            String output = callCompressor(batch, existingSummary, existingFacts);
            JsonNode root = objectMapper.readTree(output);
            List<AgentMemoryVO.SummaryFactVO> newFacts = parseFacts(root.path("facts"));
            String narrative = root.path("narrative").asText(null);

            // Union-dedup, then bound: without the quota this array grows forever and the "compression"
            // mechanism becomes the largest contributor to prompt size.
            List<AgentMemoryVO.SummaryFactVO> merged = SummaryFactQuota.apply(
                    mergeFacts(parseFactsJson(existingFacts), newFacts),
                    mem.getFactsMaxPerType(),
                    mem.getFactsMaxTotal());
            session.setRollingSummary(StringUtils.hasText(narrative) ? narrative : existingSummary);
            session.setSummaryFacts(objectMapper.writeValueAsString(merged));
            session.setSummaryUptoSeq(targetBoundary);
            session.setLastCompressedAt(OffsetDateTime.now());
            session.setCompressCount(count + 1);
            chatHistoryService.updateSession(session);

            log.atInfo()
                    .addKeyValue("conversationId", conversationId)
                    .addKeyValue("uptoSeq", targetBoundary)
                    .addKeyValue("facts", merged.size())
                    .addKeyValue("toolRows", countToolRows(batch))
                    .addKeyValue("calibrate", calibrate)
                    .addKeyValue("trigger", countTrigger ? "count" : "tokens")
                    .log("Context compressed");
        } catch (Exception exception) {
            // Degrade to truncation-only; never break the conversation.
            log.atWarn()
                    .setCause(exception)
                    .addKeyValue("conversationId", conversationId)
                    .log("Context compression failed; degrading to window truncation");
        }
    }

    /**
     * Estimated tokens of the not-yet-compressed window-relevant turns.
     *
     * <p>Deliberately reads the model-window role set (user/assistant) rather than the compressible
     * set: this estimates what the next prompt window will actually cost, and {@code tool} rows are
     * never replayed to the model as chat messages. Counting them here would trigger compression
     * early for a window that is in fact still within budget.</p>
     */
    private int uncompressedTokens(String conversationId, int upto, int total) {
        int tokens = 0;
        for (ChatMessageEntity row : chatHistoryService.readTurns(conversationId, upto + 1, total)) {
            tokens += TokenEstimator.estimate(row.getContent());
        }
        return tokens;
    }

    /** How many internal tool rows were in the batch, logged so tool-derived facts are traceable. */
    private static int countToolRows(List<ChatMessageEntity> batch) {
        int toolRows = 0;
        for (ChatMessageEntity row : batch) {
            if ("tool".equals(row.getRole())) {
                toolRows++;
            }
        }
        return toolRows;
    }

    // ---------- helpers ----------

    private String callCompressor(List<ChatMessageEntity> batch, String existingSummary, String existingFacts) {
        StringBuilder history = new StringBuilder();
        for (ChatMessageEntity row : batch) {
            history.append("[seq ").append(row.getSeq()).append("] ")
                    .append(row.getRole()).append(": ")
                    .append(row.getContent() == null ? "" : row.getContent())
                    .append('\n');
        }
        StringBuilder user = new StringBuilder();
        if (StringUtils.hasText(existingSummary)) {
            user.append("既有摘要:\n").append(existingSummary).append("\n\n");
        }
        if (StringUtils.hasText(existingFacts)) {
            user.append("既有要点:\n").append(existingFacts).append("\n\n");
        }
        user.append("对话历史:\n").append(history);

        ChatModel model = chatModelFactory.create(modelProviderService.resolveEndpoint(null, null));
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(COMPRESS_PROMPT),
                new UserMessage(user.toString())));
        // Compression fires right after a turn call and can trip the provider rate limit; retry.
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                ChatResponse response = model.call(prompt);
                if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                    throw new IllegalStateException("compressor returned empty response");
                }
                return response.getResult().getOutput().getText();
            } catch (RuntimeException exception) {
                last = exception;
                boolean rateLimited = String.valueOf(exception.getMessage()).contains("429");
                if (!rateLimited || attempt == 2) {
                    throw exception;
                }
                try {
                    Thread.sleep(3000L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
        throw last;
    }

    private List<AgentMemoryVO.SummaryFactVO> parseFacts(JsonNode node) {
        List<AgentMemoryVO.SummaryFactVO> facts = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return facts;
        }
        for (JsonNode item : node) {
            facts.add(new AgentMemoryVO.SummaryFactVO(
                    item.path("type").asText(null),
                    item.path("text").asText(null),
                    item.path("turnSeq").isInt() ? item.path("turnSeq").asInt() : null,
                    item.path("key").asText(null)));
        }
        return facts;
    }

    private List<AgentMemoryVO.SummaryFactVO> parseFactsJson(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, AgentMemoryVO.SummaryFactVO.class));
        } catch (Exception exception) {
            return new ArrayList<>();
        }
    }

    /**
     * Deterministic union-dedup: keyed by fact.key when present, else type+text. Code-side, never LLM.
     * Bounding is a separate step ({@link SummaryFactQuota}) so "no duplicates" and "stays small"
     * stay independently testable.
     */
    private List<AgentMemoryVO.SummaryFactVO> mergeFacts(
            List<AgentMemoryVO.SummaryFactVO> oldFacts, List<AgentMemoryVO.SummaryFactVO> newFacts) {
        Map<String, AgentMemoryVO.SummaryFactVO> merged = new LinkedHashMap<>();
        for (AgentMemoryVO.SummaryFactVO fact : oldFacts) {
            merged.putIfAbsent(dedupKey(fact), fact);
        }
        for (AgentMemoryVO.SummaryFactVO fact : newFacts) {
            merged.putIfAbsent(dedupKey(fact), fact);
        }
        return new ArrayList<>(merged.values());
    }

    private String dedupKey(AgentMemoryVO.SummaryFactVO fact) {
        if (StringUtils.hasText(fact.key())) {
            return fact.key();
        }
        return (fact.type() == null ? "" : fact.type()) + "|" + (fact.text() == null ? "" : fact.text());
    }
}
