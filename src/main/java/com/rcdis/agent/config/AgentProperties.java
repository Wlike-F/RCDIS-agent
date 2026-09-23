package com.rcdis.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Agent chat runtime knobs.
 *
 * <p>Kept in {@code application.yml} instead of hard-coded so operations can tighten the memory
 * window or the stream ceiling without a rebuild. Values here do not affect the model provider
 * registry, which lives in PostgreSQL.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rcdis.agent")
public class AgentProperties {

    /** Maximum number of past turns (user + assistant) fed back to the model on each request. */
    private int historyMaxMessages = 20;

    /** Maximum raw message rows retained with content per conversation. Older content is redacted. */
    private int persistenceMaxMessages = 200;

    /** Upper bound for a single streamed response, in seconds. The SSE emitter uses this too. */
    private int streamTimeoutSeconds = 180;

    /** Classpath location of the Chinese system prompt template. */
    private String systemPromptLocation = "classpath:prompts/agent-system.st";

    /** How long a proposed high-risk action stays confirmable before it expires, in minutes. */
    private int confirmationTtlMinutes = 10;

    /** Cost per 1,000 prompt tokens in the configured billing currency. */
    private double inputCostPerThousandTokens = 0.0;

    /** Cost per 1,000 completion tokens in the configured billing currency. */
    private double outputCostPerThousandTokens = 0.0;

    private final Memory memory = new Memory();

    private final Ocr ocr = new Ocr();

    private final Observability observability = new Observability();

    /** Context-compression (rolling summary + structured facts) knobs. */
    @Getter
    @Setter
    public static class Memory {
        /** Keep at most this many recent raw turns in the prompt window. */
        private int windowMessages = 10;
        /** Token budget for the raw window; truncates even if fewer than windowMessages. */
        private int windowTokens = 6000;
        /** Trigger compression when message count exceeds this. */
        private int compressThreshold = 24;
        private boolean compressEnabled = true;
        /** Re-derive the summary from raw history every N incremental compressions (anti-drift). */
        private int calibrateEvery = 5;
        /** recall_history rate limits. */
        private int recallMaxTurns = 10;
        private int recallMaxChars = 8000;

        /** Global switch: asynchronously extract cross-session semantic facts after each turn. */
        private boolean semanticExtractEnabled = true;
        /** Delay before background extraction so it doesn't collide with the turn's rate-limit window. */
        private int semanticExtractDelaySeconds = 10;
        /** Global switch: inject the user's semantic memories into the prompt prefix. */
        private boolean semanticInjectEnabled = true;
        /** Max semantic memories injected per turn. */
        private int semanticMaxInject = 10;
        /** Hard cap on durable semantic facts per user. */
        private int semanticMaxStored = 100;
    }

    /** Receipt OCR (vision-model structured extraction) knobs. */
    @Getter
    @Setter
    public static class Ocr {
        /** Global switch: recognize newly uploaded receipt images with a vision model. */
        private boolean enabled = true;
        /** Provider code from the model_provider registry hosting the vision extraction model. */
        private String providerId = "dashscope";
        /** Vision model used for structured receipt extraction. */
        private String model = "qwen-vl-ocr";
        /** How long get_receipt_ocr may block waiting for a recognition to finish. */
        private int waitTimeoutSeconds = 45;
        /** Poll interval while waiting for the recognition row to reach a terminal status. */
        private long waitPollMillis = 800;
    }

    /** Observability page knobs: period metrics window and turn-trace TTL. */
    @Getter
    @Setter
    public static class Observability {
        /** Default window (days) for the period metrics on the observability page. */
        private int periodDefaultDays = 7;
        /** Upper bound for the selectable window; larger requests are clamped. */
        private int periodMaxDays = 15;
        /** Turn-trace rows older than this are purged daily; 0 disables the TTL purge. */
        private int traceRetentionDays = 15;
    }
}
