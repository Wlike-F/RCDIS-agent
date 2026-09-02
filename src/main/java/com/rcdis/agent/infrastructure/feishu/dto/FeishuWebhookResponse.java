package com.rcdis.agent.infrastructure.feishu.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FeishuWebhookResponse(
        Integer code,
        String msg
) {

    public boolean isSuccessful() {
        return Integer.valueOf(0).equals(code);
    }
}
