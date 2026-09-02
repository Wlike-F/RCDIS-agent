package com.rcdis.agent.infrastructure.feishu.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeishuWebhookRequest(
        String timestamp,
        String sign,
        @JsonProperty("msg_type") String messageType,
        Object content
) {
}
