package com.rcdis.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "rcdis.feishu")
public class FeishuProperties {

    private boolean enabled;
    private String clientType;
    private String openApiBaseUrl;
    private String appId;
    private String appSecret;
    private String defaultReceiveIdType;
    private String defaultReceiveId;
    private String webhookUrl;
    private String signingSecret;
    private String verificationToken;
    /** Encrypt Key from the developer console; used to decrypt HTTP callback bodies. */
    private String encryptKey;
    /**
     * How card action callbacks are received: {@code none} (disabled), {@code ws} (WebSocket long
     * connection, works without a public endpoint) or {@code http} (public callback URL).
     */
    private String callbackMode;
    /** HMAC key protecting the action value embedded in approval cards from tampering. */
    private String cardActionSigningKey;
    /** Whether submitting a reimbursement pushes private approval cards to bound approvers. */
    private boolean approvalCardEnabled = true;
    private int maxAttempts;
    private long tenantTokenRefreshSkewSeconds;
}
