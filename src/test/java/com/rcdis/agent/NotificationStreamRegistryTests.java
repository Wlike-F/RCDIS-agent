package com.rcdis.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.rcdis.agent.service.NotificationStreamRegistry;

/**
 * Unit tests for the notification stream connection registry. Emitters are exercised without a
 * servlet container: Spring buffers sends until the async handler initializes, which is exactly
 * the state these assertions need.
 */
class NotificationStreamRegistryTests {

    @Test
    void subscribeRegistersConnectionAndCountsIt() {
        NotificationStreamRegistry registry = new NotificationStreamRegistry();

        SseEmitter emitter = registry.subscribe("alice");

        assertThat(emitter).isNotNull();
        assertThat(registry.openConnectionCount()).isEqualTo(1);
    }

    @Test
    void pushToOfflineRecipientIsSilentNoOp() {
        NotificationStreamRegistry registry = new NotificationStreamRegistry();

        assertThatCode(() -> registry.push("nobody", "notification", "payload"))
                .doesNotThrowAnyException();
    }

    @Test
    void pushToSubscribedRecipientDoesNotThrow() {
        NotificationStreamRegistry registry = new NotificationStreamRegistry();
        registry.subscribe("alice");

        assertThatCode(() -> registry.push("alice", "notification", "payload"))
                .doesNotThrowAnyException();
    }

    @Test
    void emitterCapClosesOldestConnection() {
        NotificationStreamRegistry registry = new NotificationStreamRegistry();

        for (int i = 0; i < 7; i++) {
            registry.subscribe("alice");
        }

        // Two leaked connections beyond the cap were completed and dropped from the registry.
        assertThat(registry.openConnectionCount()).isEqualTo(5);
    }

    @Test
    void heartbeatWithoutConnectionsIsNoOp() {
        NotificationStreamRegistry registry = new NotificationStreamRegistry();

        assertThatCode(registry::heartbeat).doesNotThrowAnyException();
    }
}
