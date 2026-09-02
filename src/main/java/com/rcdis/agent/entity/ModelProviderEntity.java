package com.rcdis.agent.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

/**
 * A model provider row. Replaces the former application.yml-only provider registry.
 */
@Getter
@Setter
@TableName("model_provider")
public class ModelProviderEntity extends BaseEntity {

    /** Stable external identifier, referenced by chat requests as {@code providerId}. */
    private String providerCode;
    private String providerName;
    /** Wire protocol; only {@code openai-compatible} is implemented. */
    private String protocol;
    private String baseUrl;
    private String chatCompletionsPath;
    private String modelsPath;

    /** AES-GCM ciphertext. Never plaintext, never exposed through any VO. */
    @JsonIgnore
    private String apiKeyCipher;

    /** Masked tail of the key for display, e.g. {@code ****abcd}. */
    private String apiKeyHint;

    private Integer timeoutSeconds;
    private BigDecimal temperature;
    private Integer maxTokens;
    private Integer isDefault;
    private Integer builtin;
    private String status;
    private String description;

    private String lastTestStatus;
    private Integer lastTestHttpStatus;
    private String lastTestMessage;
    private Long lastTestLatencyMs;
    private OffsetDateTime lastTestAt;

    @Version
    private Integer version;
}
