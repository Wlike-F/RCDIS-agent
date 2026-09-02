package com.rcdis.agent.infrastructure.feishu;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.rcdis.agent.to.FeishuMessageTO;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "rcdis.feishu", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopFeishuBotClient implements FeishuBotClient {

    @Override
    public void sendMessage(FeishuMessageTO message) {
        log.atInfo()
                .addKeyValue("messageType", message.messageType())
                .addKeyValue("target", message.target())
                .addKeyValue("idempotencyKey", message.idempotencyKey())
                .log("Feishu bot client is not configured");
    }
}
