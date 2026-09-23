package com.rcdis.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record NotificationPageRequest(
        @Min(1) long current,
        @Min(1) @Max(100) long size
) {
}
