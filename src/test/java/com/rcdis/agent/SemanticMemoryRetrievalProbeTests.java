package com.rcdis.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.service.impl.SemanticMemoryRetrievalProbeService;
import com.rcdis.agent.vo.MemoryRetrievalProbeVO;

/**
 * Hermetic guard test for the retrieval probe. The blank-input path returns before any embedding
 * call or PostgreSQL-only lane SQL, so it is safe to run on H2 without network. The real vector /
 * keyword ranking is pgvector/pg_trgm specific and is exercised manually through the admin endpoint
 * (and the developer console), consistent with the project's H2-vs-PG testing split.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "rcdis.feishu.enabled=false",
        "rcdis.feishu.client-type=noop"
})
class SemanticMemoryRetrievalProbeTests {

    @Autowired
    private SemanticMemoryRetrievalProbeService probeService;

    @AfterEach
    void clearUser() {
        CurrentUserContextHolder.clear();
    }

    @Test
    void blankQueryReturnsNoMatchWithoutTouchingEmbeddingOrLanes() {
        CurrentUserContextHolder.set(CurrentUserTO.of("probe-admin", "probe", "test", null, Set.of("ADMIN")));

        MemoryRetrievalProbeVO result = probeService.probe("some-user", "  ", 5);

        assertThat(result.mode()).isEqualTo("no_match");
        assertThat(result.hits()).isEmpty();
        assertThat(result.note()).contains("不能为空");
    }
}
