package com.rcdis.agent.service;

import java.util.List;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.rcdis.agent.config.ModelProviderProperties;
import com.rcdis.agent.entity.ModelProviderEntity;
import com.rcdis.agent.entity.ModelProviderModelEntity;
import com.rcdis.agent.infrastructure.ai.OpenAiCompatibleClient;
import com.rcdis.agent.infrastructure.ai.ProviderSecretCipher;
import com.rcdis.agent.mapper.ModelProviderMapper;
import com.rcdis.agent.mapper.ModelProviderModelMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Migrates providers declared in {@code application.yml} into the {@code model_provider} table.
 *
 * <p>Runs on startup and only inserts what is missing, so edits made through the API always win.
 * Seeded rows are marked {@code builtin=1}, which protects them from deletion. When a seeded API key
 * differs from the value currently in {@code application.yml} the database value is kept and a
 * warning is logged, because silently overwriting it would discard a key rotated through the UI.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelProviderSeeder implements ApplicationRunner {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String SOURCE_MANUAL = "MANUAL";
    private static final Integer FLAG_TRUE = Integer.valueOf(1);
    private static final Integer FLAG_FALSE = Integer.valueOf(0);

    private final ModelProviderProperties properties;
    private final ModelProviderMapper modelProviderMapper;
    private final ModelProviderModelMapper modelProviderModelMapper;
    private final ProviderSecretCipher providerSecretCipher;

    @Override
    public void run(ApplicationArguments args) {
        Map<String, ModelProviderProperties.ProviderProperties> configured = properties.getProviders();
        if (configured.isEmpty()) {
            log.atInfo().log("No model providers declared under rcdis.ai.providers; skipping seeding");
            return;
        }
        for (Map.Entry<String, ModelProviderProperties.ProviderProperties> entry : configured.entrySet()) {
            try {
                seedProvider(entry.getKey(), entry.getValue());
            } catch (RuntimeException exception) {
                // One unusable entry must not stop the application from serving the remaining ones.
                log.atError()
                        .setCause(exception)
                        .addKeyValue("providerId", entry.getKey())
                        .log("Failed to seed model provider from application.yml");
            }
        }
        ensureDefaultProvider();
    }

    private void seedProvider(String providerCode, ModelProviderProperties.ProviderProperties config) {
        ModelProviderEntity existing = findByCode(providerCode);
        if (existing == null) {
            insertProvider(providerCode, config);
            return;
        }
        reconcileApiKey(existing, config);
        ensureSeedModel(existing.getId(), config.getChatModel());
    }

    private void insertProvider(String providerCode, ModelProviderProperties.ProviderProperties config) {
        if (!StringUtils.hasText(config.getBaseUrl())) {
            log.atWarn()
                    .addKeyValue("providerId", providerCode)
                    .log("Skipping model provider without a base URL");
            return;
        }
        ModelProviderEntity entity = new ModelProviderEntity();
        entity.setProviderCode(providerCode);
        entity.setProviderName(StringUtils.hasText(config.getName()) ? config.getName() : providerCode);
        entity.setProtocol(StringUtils.hasText(config.getType())
                ? config.getType().trim()
                : OpenAiCompatibleClient.PROTOCOL);
        entity.setBaseUrl(config.getBaseUrl().trim());
        entity.setChatCompletionsPath(pathOrDefault(config.getChatCompletionsPath(),
                OpenAiCompatibleClient.DEFAULT_CHAT_COMPLETIONS_PATH));
        entity.setModelsPath(pathOrDefault(config.getModelsPath(), OpenAiCompatibleClient.DEFAULT_MODELS_PATH));
        entity.setTimeoutSeconds(config.getTimeoutSeconds() == null
                ? Integer.valueOf(OpenAiCompatibleClient.DEFAULT_TIMEOUT_SECONDS)
                : config.getTimeoutSeconds());
        entity.setBuiltin(FLAG_TRUE);
        entity.setStatus(config.isEnabled() ? STATUS_ACTIVE : STATUS_DISABLED);
        entity.setIsDefault(providerCode.equals(properties.getDefaultProvider()) ? FLAG_TRUE : FLAG_FALSE);
        entity.setDescription(StringUtils.hasText(config.getDescription())
                ? config.getDescription().trim()
                : "由 application.yml 迁移入库的内置供应商");
        entity.setVersion(FLAG_FALSE);
        if (StringUtils.hasText(config.getApiKey())) {
            String apiKey = config.getApiKey().trim();
            entity.setApiKeyCipher(providerSecretCipher.encrypt(apiKey));
            entity.setApiKeyHint(providerSecretCipher.mask(apiKey));
        }
        try {
            modelProviderMapper.insert(entity);
            log.atInfo()
                    .addKeyValue("providerId", providerCode)
                    .addKeyValue("protocol", entity.getProtocol())
                    .addKeyValue("apiKeyConfigured", entity.getApiKeyCipher() != null)
                    .log("Builtin model provider seeded from application.yml");
        } catch (DuplicateKeyException exception) {
            // Another instance seeded the same provider concurrently; ignore.
            log.atInfo()
                    .addKeyValue("providerId", providerCode)
                    .log("Builtin model provider already present");
            return;
        }
        if (StringUtils.hasText(config.getChatModel())) {
            insertModel(entity.getId(), config.getChatModel().trim(), true);
        }
    }

    /**
     * Fills in a missing API key, but never replaces one that an operator already stored.
     */
    private void reconcileApiKey(ModelProviderEntity existing, ModelProviderProperties.ProviderProperties config) {
        if (!StringUtils.hasText(config.getApiKey())) {
            return;
        }
        String configuredKey = config.getApiKey().trim();
        if (!StringUtils.hasText(existing.getApiKeyCipher())) {
            modelProviderMapper.update(null, new LambdaUpdateWrapper<ModelProviderEntity>()
                    .eq(ModelProviderEntity::getId, existing.getId())
                    .eq(ModelProviderEntity::getDeleted, FLAG_FALSE)
                    .set(ModelProviderEntity::getApiKeyCipher, providerSecretCipher.encrypt(configuredKey))
                    .set(ModelProviderEntity::getApiKeyHint, providerSecretCipher.mask(configuredKey)));
            log.atInfo()
                    .addKeyValue("providerId", existing.getProviderCode())
                    .log("Stored model provider API key was empty; filled it from application.yml");
            return;
        }
        String storedKey = providerSecretCipher.decrypt(existing.getApiKeyCipher());
        if (!configuredKey.equals(storedKey)) {
            log.atWarn()
                    .addKeyValue("providerId", existing.getProviderCode())
                    .log("The API key in application.yml differs from the stored one. The database value wins; "
                            + "update the key on the model provider page if the environment value is the current one.");
        }
    }

    private void ensureSeedModel(Long providerId, String chatModel) {
        if (!StringUtils.hasText(chatModel)) {
            return;
        }
        Long models = modelProviderModelMapper.selectCount(new LambdaQueryWrapper<ModelProviderModelEntity>()
                .eq(ModelProviderModelEntity::getProviderId, providerId)
                .eq(ModelProviderModelEntity::getDeleted, FLAG_FALSE));
        if (models > 0) {
            return;
        }
        insertModel(providerId, chatModel.trim(), true);
        log.atInfo()
                .addKeyValue("providerId", providerId)
                .addKeyValue("modelName", chatModel)
                .log("Seeded the default model for an existing model provider");
    }

    private void insertModel(Long providerId, String modelName, boolean defaultModel) {
        ModelProviderModelEntity entity = new ModelProviderModelEntity();
        entity.setProviderId(providerId);
        entity.setModelName(modelName);
        entity.setIsDefault(defaultModel ? FLAG_TRUE : FLAG_FALSE);
        entity.setSource(SOURCE_MANUAL);
        entity.setStatus(STATUS_ACTIVE);
        entity.setVersion(FLAG_FALSE);
        try {
            modelProviderModelMapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            log.atInfo()
                    .addKeyValue("providerId", providerId)
                    .addKeyValue("modelName", modelName)
                    .log("Model provider model already present");
        }
    }

    /**
     * Guarantees exactly one default provider so that agent requests without an explicit provider
     * still resolve after an upgrade.
     */
    private void ensureDefaultProvider() {
        Long defaults = modelProviderMapper.selectCount(new LambdaQueryWrapper<ModelProviderEntity>()
                .eq(ModelProviderEntity::getIsDefault, FLAG_TRUE)
                .eq(ModelProviderEntity::getDeleted, FLAG_FALSE));
        if (defaults > 0) {
            return;
        }
        ModelProviderEntity candidate = findByCode(properties.getDefaultProvider());
        if (candidate == null) {
            List<ModelProviderEntity> all = modelProviderMapper.selectList(
                    new LambdaQueryWrapper<ModelProviderEntity>()
                            .eq(ModelProviderEntity::getDeleted, FLAG_FALSE)
                            .orderByAsc(ModelProviderEntity::getId)
                            .last("limit 1"));
            candidate = all.isEmpty() ? null : all.get(0);
        }
        if (candidate == null) {
            return;
        }
        modelProviderMapper.update(null, new LambdaUpdateWrapper<ModelProviderEntity>()
                .eq(ModelProviderEntity::getId, candidate.getId())
                .eq(ModelProviderEntity::getDeleted, FLAG_FALSE)
                .set(ModelProviderEntity::getIsDefault, FLAG_TRUE));
        log.atInfo()
                .addKeyValue("providerId", candidate.getProviderCode())
                .log("Marked a model provider as default because none was flagged");
    }

    private ModelProviderEntity findByCode(String providerCode) {
        if (!StringUtils.hasText(providerCode)) {
            return null;
        }
        return modelProviderMapper.selectOne(new LambdaQueryWrapper<ModelProviderEntity>()
                .eq(ModelProviderEntity::getProviderCode, providerCode.trim())
                .eq(ModelProviderEntity::getDeleted, FLAG_FALSE)
                .last("limit 1"));
    }

    private String pathOrDefault(String path, String fallback) {
        return StringUtils.hasText(path) ? path.trim() : fallback;
    }
}
