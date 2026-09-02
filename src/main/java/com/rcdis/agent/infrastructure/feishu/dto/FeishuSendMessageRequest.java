package com.rcdis.agent.infrastructure.feishu.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FeishuSendMessageRequest(
        @JsonProperty("receive_id") String receiveId,
        @JsonProperty("msg_type") String messageType,
        String content
) {
}
