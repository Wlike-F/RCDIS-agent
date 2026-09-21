package com.rcdis.agent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import com.rcdis.agent.infrastructure.ai.ChatModelFactory;
import com.rcdis.agent.service.AgentToolCatalogService;
import com.rcdis.agent.to.ModelEndpointTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds a per-request {@link ChatClient} bound to a resolved provider endpoint.
 *
 * <p>The client is intentionally not cached: providers can be edited at runtime (base URL, model,
 * API key) and a cached client would silently keep using the previous values. {@link ChatModelFactory}
 * already documents the same reasoning for the underlying {@link OpenAiChatModel}.</p>
 *
 * <p>Tools and memory advisors are attached by the caller, not here, so this factory stays a thin
 * adapter over {@code ChatModelFactory} + the system prompt.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentChatClientFactory {

    private final ChatModelFactory chatModelFactory;
    private final SystemPromptLoader systemPromptLoader;
    private final AgentToolCatalogService agentToolCatalogService;

    public ChatClient create(ModelEndpointTO endpoint) {
        OpenAiChatModel chatModel = chatModelFactory.create(endpoint);
        return ChatClient.builder(chatModel)
                .defaultSystem(systemPromptLoader.getSystemPrompt() + agentToolCatalogService.promptCatalogue())
                .build();
    }
}
