package com.rcdis.agent.service.impl;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.rcdis.agent.dto.ChatRequest;
import com.rcdis.agent.dto.ChatResponse;
import com.rcdis.agent.service.AgentApplicationService;
import com.rcdis.agent.service.ModelProviderService;
import com.rcdis.agent.vo.ModelProviderVO;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AgentApplicationServiceImpl implements AgentApplicationService {

    private final ModelProviderService modelProviderService;

    @Override
    public ChatResponse chat(ChatRequest request) {
        ModelProviderVO provider = modelProviderService.resolveProvider(request.providerId());
        String conversationId = resolveConversationId(request.conversationId());
        String content = "RCDIS Agent skeleton is ready. Spring AI tool calling will be connected in the next milestone.";
        return new ChatResponse(conversationId, content, provider.providerId(), provider.chatModel());
    }

    private String resolveConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return conversationId.trim();
    }
}

