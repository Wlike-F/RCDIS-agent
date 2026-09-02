package com.rcdis.agent.infrastructure.feishu;

import com.rcdis.agent.to.FeishuMessageTO;

public interface FeishuBotClient {

    void sendMessage(FeishuMessageTO message);
}

