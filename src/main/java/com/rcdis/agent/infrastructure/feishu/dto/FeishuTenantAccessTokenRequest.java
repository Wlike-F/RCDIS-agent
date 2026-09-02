package com.rcdis.agent.infrastructure.feishu.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FeishuTenantAccessTokenRequest(
        @JsonProperty("app_id") String appId,
        @JsonProperty("app_secret") String appSecret
) {
}
