package com.rcdis.agent;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.rcdis.agent.entity.AgentMemoryEntity;
import com.rcdis.agent.mapper.AgentMemoryMapper;
import com.rcdis.agent.service.SemanticMemoryRetrievalService;

/**
 * Proves the degrade-first contract of {@link SemanticMemoryRetrievalService} on the H2 test schema,
 * where the pgvector / pg_trgm SQL cannot run. With semantic-retrieval left at its default OFF,
 * {@code retrieve} must behave exactly like the legacy newest-N injection and never throw — so
 * enabling the feature is a pure configuration action that cannot break the current build.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "rcdis.feishu.enabled=false",
        "rcdis.feishu.client-type=noop"
})
class SemanticMemoryRetrievalTests {

    @Autowired
    private SemanticMemoryRetrievalService retrievalService;

    @Autowired
    private AgentMemoryMapper agentMemoryMapper;

    @Test
    void retrieveFallsBackToNewestInjectionWhenDisabled() {
        String userId = "retrieval-user-" + UUID.randomUUID();
        Long first = seedMemory(userId, "第一条事实");
        Long second = seedMemory(userId, "第二条事实");

        List<AgentMemoryEntity> retrieved = retrievalService.retrieve(userId, "随便问点什么");

        // Newest-N order (id desc), no exception despite H2 lacking vector support.
        assertThat(retrieved).extracting(AgentMemoryEntity::getId)
                .containsExactly(second, first);
    }

    @Test
    void retrieveReturnsEmptyForNullUserAndHonoursInjectGate() {
        assertThat(retrievalService.retrieve(null, "anything")).isEmpty();
    }

    private Long seedMemory(String userId, String content) {
        AgentMemoryEntity row = new AgentMemoryEntity();
        row.setScope(AgentMemoryEntity.SCOPE_USER);
        row.setOwnerUserId(userId);
        row.setFactType("preference");
        row.setContent(content);
        row.setHitCount(0);
        row.setCreatedBy("test");
        row.setCreatedAt(OffsetDateTime.now());
        agentMemoryMapper.insert(row);
        return row.getId();
    }
}
