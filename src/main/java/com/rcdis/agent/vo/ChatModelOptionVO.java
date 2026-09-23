package com.rcdis.agent.vo;

/**
 * Minimal, non-sensitive model option for the chat session selector.
 *
 * <p>Exposed to every authenticated role so that non-admin users can see and switch the model
 * used for their conversation. It deliberately carries none of the registry's sensitive metadata
 * (no base URL, no API key hint, no protocol internals); only what is needed to label a provider
 * and address it from a chat request. {@code providerId} is the stable provider code.</p>
 */
public record ChatModelOptionVO(
        String providerId,
        String name,
        String chatModel,
        boolean defaultProvider
) {
}
