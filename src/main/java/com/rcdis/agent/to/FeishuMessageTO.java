package com.rcdis.agent.to;

import java.util.Map;

public record FeishuMessageTO(
        String messageType,
        String target,
        Map<String, Object> payload,
        String idempotencyKey
) {
}

