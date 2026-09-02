package com.rcdis.agent.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.rcdis.agent.entity.ModelProviderEntity;

/**
 * Provider view for the frontend and for agent-side provider routing.
 *
 * <p>Deliberately carries no API key material: only {@code apiKeyConfigured} and the masked
 * {@code apiKeyHint}. {@code providerId} is the stable provider code used by chat requests.</p>
 */
public record ModelProviderVO(
        Long id,
        String providerId,
        String name,
        String protocol,
        String baseUrl,
        String chatCompletionsPath,
        String modelsPath,
        String chatModel,
        List<ModelProviderModelVO> models,
        boolean enabled,
        boolean defaultProvider,
        boolean builtin,
        boolean apiKeyConfigured,
        String apiKeyHint,
        Integer timeoutSeconds,
        BigDecimal temperature,
        Integer maxTokens,
        String description,
        ModelProviderTestResultVO lastTest,
        Integer version,
        OffsetDateTime updatedAt
) {

    public static ModelProviderVO fromEntity(ModelProviderEntity entity, List<ModelProviderModelVO> models) {
        List<ModelProviderModelVO> safeModels = models == null ? List.of() : List.copyOf(models);
        return new ModelProviderVO(
                entity.getId(),
                entity.getProviderCode(),
                entity.getProviderName(),
                entity.getProtocol(),
                entity.getBaseUrl(),
                entity.getChatCompletionsPath(),
                entity.getModelsPath(),
                resolveChatModel(safeModels),
                safeModels,
                !"DISABLED".equals(entity.getStatus()),
                Integer.valueOf(1).equals(entity.getIsDefault()),
                Integer.valueOf(1).equals(entity.getBuiltin()),
                hasText(entity.getApiKeyCipher()),
                entity.getApiKeyHint(),
                entity.getTimeoutSeconds(),
                entity.getTemperature(),
                entity.getMaxTokens(),
                entity.getDescription(),
                toTestResult(entity),
                entity.getVersion(),
                entity.getUpdatedAt());
    }

    /**
     * The model an agent request uses when the caller does not name one explicitly.
     */
    public static String resolveChatModel(List<ModelProviderModelVO> models) {
        return models.stream()
                .filter(ModelProviderModelVO::enabled)
                .filter(ModelProviderModelVO::defaultModel)
                .map(ModelProviderModelVO::modelName)
                .findFirst()
                .or(() -> models.stream()
                        .filter(ModelProviderModelVO::enabled)
                        .map(ModelProviderModelVO::modelName)
                        .findFirst())
                .orElse(null);
    }

    private static ModelProviderTestResultVO toTestResult(ModelProviderEntity entity) {
        if (!hasText(entity.getLastTestStatus())) {
            return null;
        }
        return new ModelProviderTestResultVO(
                entity.getLastTestStatus(),
                entity.getLastTestMessage(),
                entity.getLastTestHttpStatus(),
                entity.getLastTestLatencyMs(),
                entity.getLastTestAt());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
