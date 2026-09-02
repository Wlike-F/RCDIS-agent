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
    private int maxAttempts;
    private long tenantTokenRefreshSkewSeconds;
}
