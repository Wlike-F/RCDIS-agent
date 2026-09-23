package com.rcdis.agent.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;

/**
 * Live SSE connections for in-app notifications.
 *
 * <p>The database stays the source of truth: {@code app_notification} rows are always written first,
 * this registry only accelerates delivery for users who happen to be online. A push failure or a
 * missing connection loses nothing, because the client reconciles through the REST endpoints on
 * reconnect and on a low-frequency timer.</p>
 *
 * <p>Emitters use no server-side timeout; liveness is maintained by the comment ping below and by
 * the client's own reconnect loop, which also covers network blips and backend restarts.</p>
 */
@Slf4j
@Component
public class NotificationStreamRegistry {

    /** An attacker-controlled or leaking client must not pin unbounded emitters on one account. */
    private static final int MAX_EMITTERS_PER_RECIPIENT = 5;

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emittersByRecipient = new ConcurrentHashMap<>();

    /**
     * Registers a new long-lived connection for the recipient and sends an immediate {@code hello}
     * event so the client can confirm the stream is alive (Spring buffers sends that happen before
     * the handler finishes initialization).
     */
    public SseEmitter subscribe(String recipient) {
        SseEmitter emitter = new SseEmitter(0L);
        CopyOnWriteArrayList<SseEmitter> emitters =
                emittersByRecipient.computeIfAbsent(recipient, key -> new CopyOnWriteArrayList<>());
        // Close the oldest connection when a tab leaks reconnects; the newest one is the live view.
        while (emitters.size() >= MAX_EMITTERS_PER_RECIPIENT) {
            SseEmitter oldest = emitters.remove(0);
            oldest.complete();
        }
        emitters.add(emitter);
        emitter.onCompletion(() -> remove(recipient, emitter));
        emitter.onTimeout(() -> remove(recipient, emitter));
        emitter.onError(failure -> remove(recipient, emitter));
        try {
            emitter.send(SseEmitter.event().name("hello").data(Map.of("recipient", recipient)));
        } catch (IOException | IllegalStateException exception) {
            remove(recipient, emitter);
            throw new IllegalStateException(
                    "Failed to open notification stream for recipient=" + recipient, exception);
        }
        log.atInfo()
                .addKeyValue("recipient", recipient)
                .addKeyValue("openConnections", emitters.size())
                .log("Notification stream subscribed");
        return emitter;
    }

    /**
     * Best-effort push to every live connection of one recipient. Never throws: an offline recipient
     * or a dead connection simply falls back to the next REST reconcile.
     */
    public void push(String recipient, String eventName, Object payload) {
        List<SseEmitter> emitters = emittersByRecipient.get(recipient);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException exception) {
                remove(recipient, emitter);
                emitter.completeWithError(exception);
            }
        }
    }

    /**
     * Comment ping keeps proxies and browsers from idle-closing the connection. Comments are not
     * dispatched as SSE messages, so the client parser ignores them without extra handling.
     */
    @Scheduled(fixedRate = 25_000L)
    public void heartbeat() {
        emittersByRecipient.forEach((recipient, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException exception) {
                    remove(recipient, emitter);
                }
            }
        });
    }

    public int openConnectionCount() {
        return emittersByRecipient.values().stream().mapToInt(List::size).sum();
    }

    private void remove(String recipient, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByRecipient.get(recipient);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emittersByRecipient.remove(recipient, emitters);
            }
        }
    }
}
