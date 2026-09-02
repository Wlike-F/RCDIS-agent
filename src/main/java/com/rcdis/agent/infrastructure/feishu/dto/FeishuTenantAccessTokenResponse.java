package com.rcdis.agent.infrastructure.feishu.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FeishuTenantAccessTokenResponse(
        Integer code,
        String msg,
        @JsonProperty("tenant_access_token") String tenantAccessToken,
        Integer expire
) {

    public boolean isSuccessful() {
        return Integer.valueOf(0).equals(code);
    }
}
