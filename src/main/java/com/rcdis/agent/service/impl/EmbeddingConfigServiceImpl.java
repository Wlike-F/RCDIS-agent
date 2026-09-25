package com.rcdis.agent.service.impl;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.service.AppSettingService;
import com.rcdis.agent.service.EmbeddingConfigService;
import com.rcdis.agent.service.ModelProviderService;
import com.rcdis.agent.vo.EmbeddingConfigVO;

import lombok.RequiredArgsConstructor;

/**
 * {@code app_setting}-backed embedding configuration, mirroring {@link OcrConfigServiceImpl}.
 *
 * <p>Read precedence is always {@code app_setting} then {@link AgentProperties} defaults, so a value
 * chosen in the UI wins without a restart while a fresh install still gets the yml default. The
 * provider may be left blank to reuse the default chat provider (and its key), which is the common
 * case (DashScope hosts both chat and embeddings).</p>
 */
@Service
@RequiredArgsConstructor
public class EmbeddingConfigServiceImpl implements EmbeddingConfigService {

    public static final String KEY_ENABLED = "embedding.enabled";
    public static final String KEY_PROVIDER = "embedding.provider-id";
    public static final String KEY_MODEL = "embedding.model";
    public static final String KEY_PATH = "embedding.path";

    private final AppSettingService appSettingService;
    private final ModelProviderService modelProviderService;
    private final AgentProperties agentProperties;

    @Override
    public EmbeddingConfigVO current() {
        return new EmbeddingConfigVO(
                enabled(),
                providerId(),
                model(),
                embeddingsPath(),
                dimension(),
                modelProviderService.listProviders());
    }

    @Override
    public EmbeddingConfigVO update(Boolean enabled, String providerId, String model, String embeddingsPath) {
        if (enabled != null) {
            appSettingService.put(KEY_ENABLED, enabled.toString());
        }
        if (providerId != null) {
            String trimmed = providerId.trim();
            if (StringUtils.hasText(trimmed)) {
                boolean exists = modelProviderService.listProviders().stream()
                        .anyMatch(provider -> provider.providerId().equalsIgnoreCase(trimmed));
                if (!exists) {
                    throw new BusinessException(
                            "EMBEDDING_PROVIDER_NOT_FOUND",
                            "模型供应商不存在：" + trimmed,
                            HttpStatus.BAD_REQUEST);
                }
                appSettingService.put(KEY_PROVIDER, trimmed);
            } else {
                // Explicit blank clears the override so embeddings fall back to the default provider.
                appSettingService.put(KEY_PROVIDER, "");
            }
        }
        if (StringUtils.hasText(model)) {
            appSettingService.put(KEY_MODEL, model.trim());
        }
        if (StringUtils.hasText(embeddingsPath)) {
            appSettingService.put(KEY_PATH, embeddingsPath.trim());
        }
        return current();
    }

    @Override
    public boolean enabled() {
        String raw = appSettingService.get(KEY_ENABLED);
        if (StringUtils.hasText(raw)) {
            return Boolean.parseBoolean(raw.trim());
        }
        return agentProperties.getMemory().isSemanticRetrievalEnabled();
    }

    @Override
    public String providerId() {
        // Blank means "use the default chat provider"; no yml fallback on purpose.
        return trimToEmpty(appSettingService.get(KEY_PROVIDER));
    }

    @Override
    public String model() {
        return firstNonBlank(appSettingService.get(KEY_MODEL), agentProperties.getMemory().getEmbeddingModel());
    }

    @Override
    public String embeddingsPath() {
        return firstNonBlank(appSettingService.get(KEY_PATH), agentProperties.getMemory().getEmbeddingsPath());
    }

    @Override
    public int dimension() {
        return agentProperties.getMemory().getEmbeddingDimension();
    }

    private static String firstNonBlank(String override, String fallback) {
        return StringUtils.hasText(override) ? override.trim() : fallback;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
