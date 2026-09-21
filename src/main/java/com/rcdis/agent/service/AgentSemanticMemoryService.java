package com.rcdis.agent.service;

import java.util.List;

import com.rcdis.agent.entity.AgentMemoryEntity;
import com.rcdis.agent.vo.MemorySettingVO;
import com.rcdis.agent.vo.SemanticMemoryVO;

/**
 * Cross-session semantic memory: per-user read/write switches, async extraction, and injection.
 */
public interface AgentSemanticMemoryService {

    /** Current user's switches merged with the global config switches. */
    MemorySettingVO getSetting();

    /** Updates the current user's extract / inject switches. */
    void updateSetting(boolean extractEnabled, boolean injectEnabled);

    /** Effective write switch = global semanticExtract && user extract. */
    boolean effectiveExtract(String userId);

    /** Effective read switch = global semanticInject && user inject. */
    boolean effectiveInject(String userId);

    /** Memories to inject into the prompt prefix for this user (most recent first). */
    List<AgentMemoryEntity> loadForInjection(String userId);

    /** Asynchronously extracts durable cross-session facts from a finished turn. */
    void extractAsync(String userId, String createdBy, String conversationId, Integer seq,
                      String userText, String assistantText);

    /** Asynchronously records that the given memories were injected (hit stats). */
    void bumpHitsAsync(List<Long> memoryIds);

    /** All memories of the current user, for the memory panel. */
    List<SemanticMemoryVO> listForCurrentUser();
}
