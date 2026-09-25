package com.rcdis.agent;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.service.EmbeddingConfigService;
import com.rcdis.agent.vo.EmbeddingConfigVO;

/**
 * Integration tests for the runtime-manageable embedding configuration, mirroring the receipt-OCR
 * config contract: {@code app_setting} choices take effect immediately and provider input is
 * validated on update. Runs on H2 without any live embedding call.
 *
 * <p>The embedding config keys are GLOBAL (one shared {@code app_setting} row per key, exactly like
 * OCR), so these tests never assume pristine defaults — each writes the value it asserts on, keeping
 * them order-independent across the shared test database.</p>
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "rcdis.feishu.enabled=false",
        "rcdis.feishu.client-type=noop"
})
class EmbeddingConfigTests {

    @Autowired
    private EmbeddingConfigService embeddingConfigService;

    @AfterEach
    void clearUser() {
        CurrentUserContextHolder.clear();
    }

    @Test
    void updateSwitchTakesEffectImmediatelyBothWays() {
        CurrentUserContextHolder.set(admin("emb-user-switch"));

        assertThat(embeddingConfigService.update(false, null, null, null).enabled()).isFalse();
        assertThat(embeddingConfigService.current().enabled()).isFalse();

        assertThat(embeddingConfigService.update(true, null, null, null).enabled()).isTrue();
        assertThat(embeddingConfigService.current().enabled()).isTrue();
    }

    @Test
    void updateModelAndPathPersist() {
        CurrentUserContextHolder.set(admin("emb-user-model"));

        EmbeddingConfigVO updated = embeddingConfigService.update(
                null, null, "sensenova-embedding", "/v1/qd/embeddings");

        assertThat(updated.model()).isEqualTo("sensenova-embedding");
        assertThat(updated.embeddingsPath()).isEqualTo("/v1/qd/embeddings");
        // Dimension is fixed by the migration/env, never by this endpoint.
        assertThat(updated.dimension()).isEqualTo(embeddingConfigService.dimension());
    }

    @Test
    void nullFieldsKeepCurrentValues() {
        CurrentUserContextHolder.set(admin("emb-user-keep"));
        String beforeModel = embeddingConfigService.update(
                null, null, "keep-me-model", null).model();

        EmbeddingConfigVO after = embeddingConfigService.update(true, null, null, null);

        assertThat(after.model()).isEqualTo(beforeModel).isEqualTo("keep-me-model");
        assertThat(after.enabled()).isTrue();
    }

    @Test
    void blankProviderIsAllowedAndMeansDefaultProvider() {
        CurrentUserContextHolder.set(admin("emb-user-blank"));

        EmbeddingConfigVO updated = embeddingConfigService.update(null, "", null, null);

        assertThat(updated.providerId()).isEmpty();
    }

    @Test
    void unknownProviderIsRejected() {
        CurrentUserContextHolder.set(admin("emb-user-unknown"));

        assertThatThrownBy(() -> embeddingConfigService.update(
                null, "no-such-provider-" + System.nanoTime(), null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no-such-provider");
    }

    private static CurrentUserTO admin(String id) {
        return CurrentUserTO.of(id, "embedding-admin", "test", null, Set.of("ADMIN"));
    }
}
