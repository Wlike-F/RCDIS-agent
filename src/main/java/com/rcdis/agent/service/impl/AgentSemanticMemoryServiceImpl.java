package com.rcdis.agent.service.impl;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.agent.UntrustedContextPolicy;
import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.entity.AgentMemoryEntity;
import com.rcdis.agent.entity.AgentUserMemorySettingEntity;
import com.rcdis.agent.infrastructure.ai.ChatModelFactory;
import com.rcdis.agent.mapper.AgentMemoryMapper;
import com.rcdis.agent.mapper.AgentUserMemorySettingMapper;
import com.rcdis.agent.service.AgentSemanticMemoryService;
import com.rcdis.agent.service.ModelProviderService;
import com.rcdis.agent.vo.MemorySettingVO;
import com.rcdis.agent.vo.SemanticMemoryVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Cross-session semantic memory with read/write-separated switches.
 *
 * <p>Effective behaviour is the AND of a global config switch and a per-user switch, for both the
 * write path (async extraction after a turn) and the read path (injection into the prompt prefix).
 * Extraction stores extractive snippets only, so numbers/identifiers are never paraphrased.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSemanticMemoryServiceImpl implements AgentSemanticMemoryService {

    private static final int MEMORY_CONTENT_MAX_LENGTH = 1_000;
    private static final int EXTRACTION_MAX_FACTS = 5;

    private static final String EXTRACT_PROMPT = """
            你是跨会话记忆抽取器。输入为一轮对话（user 与 assistant 文本）。
            任务：挑出"跨会话长期有价值"的事实片段：用户偏好 / 身份 / 约束 / 长期决定 / 项目口径。
            要求：原样截取原文片段（不得改写数字、不得省略单位、不得编造）；只挑下次会话仍然有用的内容。
            仅输出一个 JSON 数组：[{"factType":"preference|identity|constraint|decision|project_fact","content":"<原文片段>"}]
            若没有值得长期记住的事实，输出 []。不要输出任何其他文字。
            """;

    private final AgentMemoryMapper agentMemoryMapper;
    private final AgentUserMemorySettingMapper settingMapper;
    private final AgentProperties agentProperties;
    private final ModelProviderService modelProviderService;
    private final ChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;
    private final UntrustedContextPolicy untrustedContextPolicy;

    @Override
    public MemorySettingVO getSetting() {
        CurrentUserTO user = CurrentUserContextHolder.currentOrAnonymous();
        AgentUserMemorySettingEntity setting = findSetting(user.userId());
        AgentProperties.Memory mem = agentProperties.getMemory();
        return new MemorySettingVO(
                user.userId(),
                setting == null || setting.getExtractEnabled() == null || setting.getExtractEnabled(),
                setting == null || setting.getInjectEnabled() == null || setting.getInjectEnabled(),
                mem.isSemanticExtractEnabled(),
                mem.isSemanticInjectEnabled());
    }

    @Override
    public void updateSetting(boolean extractEnabled, boolean injectEnabled) {
        CurrentUserTO user = CurrentUserContextHolder.currentOrAnonymous();
        AgentUserMemorySettingEntity setting = findSetting(user.userId());
        if (setting == null) {
            setting = new AgentUserMemorySettingEntity();
            setting.setUserId(user.userId());
            setting.setExtractEnabled(extractEnabled);
            setting.setInjectEnabled(injectEnabled);
            setting.setUpdatedAt(OffsetDateTime.now());
            settingMapper.insert(setting);
        } else {
            setting.setExtractEnabled(extractEnabled);
            setting.setInjectEnabled(injectEnabled);
            setting.setUpdatedAt(OffsetDateTime.now());
            settingMapper.updateById(setting);
        }
    }

    @Override
    public boolean effectiveExtract(String userId) {
        if (!agentProperties.getMemory().isSemanticExtractEnabled()) {
            return false;
        }
        AgentUserMemorySettingEntity setting = findSetting(userId);
        return setting == null || setting.getExtractEnabled() == null || setting.getExtractEnabled();
    }

    @Override
    public boolean effectiveInject(String userId) {
        if (!agentProperties.getMemory().isSemanticInjectEnabled()) {
            return false;
        }
        AgentUserMemorySettingEntity setting = findSetting(userId);
        return setting == null || setting.getInjectEnabled() == null || setting.getInjectEnabled();
    }

    @Override
    public List<AgentMemoryEntity> loadForInjection(String userId) {
        if (userId == null || !effectiveInject(userId)) {
            return List.of();
        }
        int limit = agentProperties.getMemory().getSemanticMaxInject();
        LambdaQueryWrapper<AgentMemoryEntity> wrapper = new LambdaQueryWrapper<AgentMemoryEntity>()
                .eq(AgentMemoryEntity::getOwnerUserId, userId)
                .orderByDesc(AgentMemoryEntity::getId)
                .last("LIMIT " + limit);
        List<AgentMemoryEntity> rows = agentMemoryMapper.selectList(wrapper);
        return rows == null ? List.of() : rows;
    }

    @Override
    @Async("memoryTaskExecutor")
    public void extractAsync(String userId, String createdBy, String conversationId, Integer seq,
                             String userText, String assistantText) {
        if (userId == null || !effectiveExtract(userId)) {
            return;
        }
        if (!StringUtils.hasText(userText) && !StringUtils.hasText(assistantText)) {
            return;
        }
        try {
            // Let the provider's per-minute rate-limit window from the turn call elapse first.
            int delay = agentProperties.getMemory().getSemanticExtractDelaySeconds();
            if (delay > 0) {
                Thread.sleep(delay * 1000L);
            }
            String output = callExtractor(userText, assistantText);
            JsonNode root = objectMapper.readTree(output);
            if (root == null || !root.isArray() || root.isEmpty()) {
                return;
            }
            int inserted = 0;
            for (JsonNode item : root) {
                if (inserted >= EXTRACTION_MAX_FACTS || memoryCount(userId) >= maxStoredMemories()) {
                    break;
                }
                String content = item.path("content").asText(null);
                if (!StringUtils.hasText(content)) {
                    continue;
                }
                if (!untrustedContextPolicy.safeForLongTermMemory(content)) {
                    log.atWarn()
                            .addKeyValue("userId", userId)
                            .addKeyValue("conversationId", conversationId)
                            .log("Rejected instruction-like semantic memory candidate");
                    continue;
                }
                if (exists(userId, content)) {
                    continue; // exact-match dedup
                }
                AgentMemoryEntity row = new AgentMemoryEntity();
                row.setScope(AgentMemoryEntity.SCOPE_USER);
                row.setOwnerUserId(userId);
                row.setFactType(item.path("factType").asText(null));
                row.setContent(limit(content.trim(), MEMORY_CONTENT_MAX_LENGTH));
                row.setSourceConversationId(conversationId);
                row.setSourceSeq(seq);
                row.setHitCount(0);
                row.setCreatedBy(createdBy);
                row.setCreatedAt(OffsetDateTime.now());
                agentMemoryMapper.insert(row);
                inserted++;
            }
            if (inserted > 0) {
                log.atInfo()
                        .addKeyValue("userId", userId)
                        .addKeyValue("conversationId", conversationId)
                        .addKeyValue("inserted", inserted)
                        .log("Semantic memories extracted");
            }
        } catch (Exception exception) {
            // Memory extraction is best-effort; never break the conversation.
            log.atWarn()
                    .setCause(exception)
                    .addKeyValue("userId", userId)
                    .addKeyValue("conversationId", conversationId)
                    .log("Semantic memory extraction failed; skipping");
        }
    }

    @Override
    @Async("memoryTaskExecutor")
    public void bumpHitsAsync(List<Long> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return;
        }
        try {
            LambdaUpdateWrapper<AgentMemoryEntity> wrapper = new LambdaUpdateWrapper<AgentMemoryEntity>()
                    .in(AgentMemoryEntity::getId, memoryIds)
                    .setSql("hit_count = hit_count + 1")
                    .set(AgentMemoryEntity::getLastHitAt, OffsetDateTime.now());
            agentMemoryMapper.update(null, wrapper);
        } catch (RuntimeException exception) {
            log.atWarn().setCause(exception).log("Failed to bump semantic memory hit stats");
        }
    }

    @Override
    public List<SemanticMemoryVO> listForCurrentUser() {
        CurrentUserTO user = CurrentUserContextHolder.currentOrAnonymous();
        LambdaQueryWrapper<AgentMemoryEntity> wrapper = new LambdaQueryWrapper<AgentMemoryEntity>()
                .eq(AgentMemoryEntity::getOwnerUserId, user.userId())
                .orderByDesc(AgentMemoryEntity::getId)
                .last("LIMIT " + maxStoredMemories());
        List<AgentMemoryEntity> rows = agentMemoryMapper.selectList(wrapper);
        List<SemanticMemoryVO> result = new ArrayList<>();
        if (rows != null) {
            for (AgentMemoryEntity row : rows) {
                result.add(new SemanticMemoryVO(
                        row.getId(), row.getScope(), row.getFactType(), row.getContent(),
                        row.getSourceConversationId(), row.getSourceSeq(), row.getHitCount(),
                        row.getLastHitAt(), row.getCreatedAt()));
            }
        }
        return result;
    }

    // ---------- helpers ----------

    private AgentUserMemorySettingEntity findSetting(String userId) {
        if (userId == null) {
            return null;
        }
        LambdaQueryWrapper<AgentUserMemorySettingEntity> wrapper =
                new LambdaQueryWrapper<AgentUserMemorySettingEntity>()
                        .eq(AgentUserMemorySettingEntity::getUserId, userId)
                        .last("LIMIT 1");
        return settingMapper.selectOne(wrapper);
    }

    private boolean exists(String userId, String content) {
        LambdaQueryWrapper<AgentMemoryEntity> wrapper = new LambdaQueryWrapper<AgentMemoryEntity>()
                .eq(AgentMemoryEntity::getOwnerUserId, userId)
                .eq(AgentMemoryEntity::getContent, content.trim())
                .last("LIMIT 1");
        return agentMemoryMapper.selectOne(wrapper) != null;
    }

    private long memoryCount(String userId) {
        Long count = agentMemoryMapper.selectCount(new LambdaQueryWrapper<AgentMemoryEntity>()
                .eq(AgentMemoryEntity::getOwnerUserId, userId));
        return count == null ? 0L : count;
    }

    private int maxStoredMemories() {
        return Math.max(1, agentProperties.getMemory().getSemanticMaxStored());
    }

    private static String limit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String callExtractor(String userText, String assistantText) {
        StringBuilder body = new StringBuilder();
        body.append("user: ").append(userText == null ? "" : userText).append('\n');
        body.append("assistant: ").append(assistantText == null ? "" : assistantText).append('\n');
        ChatModel model = chatModelFactory.create(modelProviderService.resolveEndpoint(null, null));
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(EXTRACT_PROMPT),
                new UserMessage(body.toString())));
        // The extractor fires right after the main turn call, so it can trip the provider's
        // rate limit; back off and retry a couple of times before giving up.
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                ChatResponse response = model.call(prompt);
                if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                    throw new IllegalStateException("semantic extractor returned empty response");
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
}
