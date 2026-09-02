package com.rcdis.agent.infrastructure.feishu.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FeishuSendMessageResponse(
        Integer code,
        String msg,
        JsonNode data
) {

    public boolean isSuccessful() {
        return Integer.valueOf(0).equals(code);
    }
}
