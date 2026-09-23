package com.rcdis.agent.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.service.AppSettingService;
import com.rcdis.agent.service.ModelProviderService;
import com.rcdis.agent.service.OcrConfigService;
import com.rcdis.agent.vo.ModelProviderVO;
import com.rcdis.agent.vo.OcrConfigVO;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OcrConfigServiceImpl implements OcrConfigService {

    public static final String KEY_ENABLED = "ocr.enabled";
    public static final String KEY_PROVIDER = "ocr.provider-id";
    public static final String KEY_MODEL = "ocr.model";

    private final AppSettingService appSettingService;
    private final ModelProviderService modelProviderService;
    private final AgentProperties agentProperties;

    @Override
    public OcrConfigVO current() {
        boolean enabled = agentProperties.getOcr().isEnabled();
        String enabledRaw = appSettingService.get(KEY_ENABLED);
        if (StringUtils.hasText(enabledRaw)) {
            enabled = Boolean.parseBoolean(enabledRaw.trim());
        }
        List<ModelProviderVO> providers = modelProviderService.listProviders();
        String providerId = firstNonBlank(
                appSettingService.get(KEY_PROVIDER), agentProperties.getOcr().getProviderId());
        String model = firstNonBlank(appSettingService.get(KEY_MODEL), agentProperties.getOcr().getModel());
        if (!StringUtils.hasText(model)) {
            // Nothing configured: route to a vision-capable model instead of guessing, so receipt
            // images are never sent to a text-only model.
            Map.Entry<String, String> autoPick = firstVisionModel(providers);
            if (autoPick != null) {
                providerId = autoPick.getKey();
                model = autoPick.getValue();
            }
        }
        return new OcrConfigVO(enabled, providerId, model, providers);
    }

    @Override
    public OcrConfigVO update(Boolean enabled, String providerId, String model) {
        if (enabled != null) {
            appSettingService.put(KEY_ENABLED, enabled.toString());
        }
        if (StringUtils.hasText(providerId)) {
            String trimmed = providerId.trim();
            boolean exists = modelProviderService.listProviders().stream()
                    .anyMatch(provider -> provider.providerId().equalsIgnoreCase(trimmed));
            if (!exists) {
                throw new BusinessException(
                        "OCR_PROVIDER_NOT_FOUND",
                        "模型供应商不存在：" + providerId,
                        HttpStatus.BAD_REQUEST);
            }
            appSettingService.put(KEY_PROVIDER, trimmed);
        }
        if (StringUtils.hasText(model)) {
            appSettingService.put(KEY_MODEL, model.trim());
        }
        return current();
    }

    private static String firstNonBlank(String override, String fallback) {
        return StringUtils.hasText(override) ? override.trim() : fallback;
    }

    /** First enabled VISION-capable model among enabled providers, as provider code + model name. */
    private static Map.Entry<String, String> firstVisionModel(List<ModelProviderVO> providers) {
        return providers.stream()
                .filter(ModelProviderVO::enabled)
                .flatMap(provider -> provider.models().stream()
                        .filter(model -> model.enabled() && "VISION".equalsIgnoreCase(model.capability()))
                        .map(model -> Map.entry(provider.providerId(), model.modelName())))
                .findFirst()
                .orElse(null);
    }
}
