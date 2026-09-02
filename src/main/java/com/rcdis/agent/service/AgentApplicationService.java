package com.rcdis.agent.service;

import com.rcdis.agent.dto.ChatRequest;
import com.rcdis.agent.dto.ChatResponse;

public interface AgentApplicationService {

    ChatResponse chat(ChatRequest request);
}

