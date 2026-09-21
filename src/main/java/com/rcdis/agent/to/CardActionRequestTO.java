package com.rcdis.agent.to;

import java.util.Map;

/**
 * One Feishu card action, normalized so that both the WebSocket long connection and the HTTP
 * callback endpoint can feed the same handler.
 *
 * @param eventId           {@code header.event_id}, used as the idempotency key because Feishu
 *                          retries a callback that is not answered within 3 seconds
 * @param verificationToken {@code header.token}, present in HTTP mode; the long connection
 *                          authenticates the channel itself so it is null there
 * @param actionValue       {@code event.action.value}, the developer data bound to the button
 * @param formValue         {@code event.action.form_value}, submitted form container values
 * @param rawPayload        the callback body as received, stored for auditability
 */
public record CardActionRequestTO(
        String eventId,
        String eventType,
        String verificationToken,
        String openId,
        String userId,
        String tenantKey,
        String openChatId,
        String openMessageId,
        String actionTag,
        Map<String, Object> actionValue,
        Map<String, Object> formValue,
        String rawPayload
) {
}
