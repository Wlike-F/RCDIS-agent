package com.rcdis.agent.infrastructure.ai;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.to.ModelEndpointTO;

import lombok.extern.slf4j.Slf4j;

/**
 * Builds Spring AI chat models from provider configuration resolved out of PostgreSQL.
 *
 * <p>A fresh instance is created per call instead of being cached, because providers can be edited at
 * runtime and a cached client would silently keep using the previous base URL, model name, or key.</p>
 */
@Slf4j
@Component
public class ChatModelFactory {

    /** Placeholder credential for endpoints that need none, such as a local Ollama instance. */
    private static final String KEYLESS_PLACEHOLDER = "not-required";

    public OpenAiChatModel create(ModelEndpointTO endpoint) {
        if (!OpenAiCompatibleClient.PROTOCOL.equals(endpoint.protocol())) {
            throw new BusinessException(
                    "MODEL_PROVIDER_PROTOCOL_UNSUPPORTED",
                    "暂不支持的接入协议：" + endpoint.protocol() + "。当前仅支持 "
                            + OpenAiCompatibleClient.PROTOCOL + "（OpenAI 兼容协议）。");
        }
        if (!StringUtils.hasText(endpoint.modelName())) {
            throw new BusinessException(
                    "MODEL_PROVIDER_MODEL_MISSING",
                    "供应商 " + endpoint.providerId() + " 尚未配置默认模型，请先在模型列表中添加并指定一个默认模型。");
        }
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(endpoint.hasApiKey() ? endpoint.apiKey() : KEYLESS_PLACEHOLDER)
                .completionsPath(endpoint.chatCompletionsPath())
                .build();

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(endpoint.modelName());
        if (endpoint.temperature() != null) {
            options.temperature(endpoint.temperature().doubleValue());
        }
        if (endpoint.maxTokens() != null) {
            options.maxTokens(endpoint.maxTokens());
        }

        // Base URL and model name are logged for observability; the API key never is.
        log.atDebug()
                .addKeyValue("providerId", endpoint.providerId())
                .addKeyValue("baseUrl", endpoint.baseUrl())
                .addKeyValue("modelName", endpoint.modelName())
                .log("Built an OpenAI compatible chat model from stored provider configuration");
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options.build())
                .build();
    }
}
