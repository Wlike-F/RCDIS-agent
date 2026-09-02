package com.rcdis.agent.vo;

import java.time.OffsetDateTime;

public record HealthVO(
        String status,
        String application,
        OffsetDateTime time
) {
}

