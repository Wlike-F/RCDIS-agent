package com.rcdis.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record NotificationOutboxPageRequest(
        @Min(1) long current,
        @Min(1) @Max(500) long size,
        @Pattern(regexp = "PENDING|SENT|FAILED") String status,
        @Pattern(regexp = "FEISHU_WEBHOOK|FEISHU_APP|FEISHU_NOOP") String channel,
        @Size(max = 128) String keyword
) {
}
