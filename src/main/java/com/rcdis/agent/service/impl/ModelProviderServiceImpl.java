package com.rcdis.agent.service.impl;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.rcdis.agent.common.aop.AuditOperation;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.dto.ModelProviderCreateRequest;
import com.rcdis.agent.dto.ModelProviderDeleteRequest;
import com.rcdis.agent.dto.ModelProviderDiscoveryRequest;
import com.rcdis.agent.dto.ModelProviderModelInput;
import com.rcdis.agent.dto.ModelProviderTestRequest;
import com.rcdis.agent.dto.ModelProviderTestResponse;
import com.rcdis.agent.dto.ModelProviderUpdateRequest;
import com.rcdis.agent.entity.ModelProviderEntity;
import com.rcdis.agent.entity.ModelProviderModelEntity;
import com.rcdis.agent.infrastructure.ai.OpenAiCompatibleClient;
import com.rcdis.agent.infrastructure.ai.ProviderSecretCipher;
import com.rcdis.agent.mapper.ModelProviderMapper;
import com.rcdis.agent.mapper.ModelProviderModelMapper;
import com.rcdis.agent.service.ModelProviderService;
import com.rcdis.agent.to.ModelEndpointTO;
import com.rcdis.agent.to.ProviderProbeTO;
import com.rcdis.agent.vo.ModelProviderDiscoveryVO;
import com.rcdis.agent.vo.ModelProviderModelVO;
import com.rcdis.agent.vo.ModelProviderTestResultVO;
import com.rcdis.agent.vo.ModelProviderVO;
import com.rcdis.agent.vo.ModelProtocolVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PostgreSQL backed model provider registry.
 *
 * <p>API keys are encrypted with {@link ProviderSecretCipher} before they are written and are never
 * returned by any read path. Connectivity checks issue real HTTP requests through
 * {@link OpenAiCompatibleClient} instead of only inspecting the configuration.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelProviderServiceImpl implements ModelProviderService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String SOURCE_MANUAL = "MANUAL";
    private static final String SOURCE_DISCOVERED = "DISCOVERED";
    private static final Integer FLAG_TRUE = Integer.valueOf(1);
    private static final Integer FLAG_FALSE = Integer.valueOf(0);
    private static final String PROBE_NOT_READY = "NOT_READY";
    private static final int MAX_MODELS_PER_PROVIDER = 50;
    private static final String TARGET_TYPE = "MODEL_PROVIDER";

    /**
     * Protocols a custom endpoint may implement. Only the OpenAI compatible one is wired up; the
     * others are listed so that the UI can state clearly what is not supported yet.
     */
    private static final List<ModelProtocolVO> PROTOCOLS = List.of(
            new ModelProtocolVO(
                    OpenAiCompatibleClient.PROTOCOL,
                    "OpenAI 兼容",
                    "端点需提供 POST {baseUrl}/v1/chat/completions 与 GET {baseUrl}/v1/models，"
                            + "请求体为 {model, messages[], max_tokens, temperature, stream}，"
                            + "鉴权使用 Authorization: Bearer <apiKey>。两个路径均可按供应商单独覆盖。",
                    OpenAiCompatibleClient.DEFAULT_CHAT_COMPLETIONS_PATH,
                    OpenAiCompatibleClient.DEFAULT_MODELS_PATH,
                    true,
                    true,
                    List.of("OpenAI", "DeepSeek", "通义千问 DashScope", "Moonshot Kimi", "智谱 GLM",
                            "SiliconFlow", "OpenRouter", "Groq", "Together AI", "Ollama", "vLLM",
                            "LM Studio", "Xinference", "One-API / New-API")),
            new ModelProtocolVO(
                    "anthropic",
                    "Anthropic Messages",
                    "Claude 原生协议：POST {baseUrl}/v1/messages，使用 x-api-key 头与 anthropic-version 鉴权。"
                            + "当前版本尚未实现。",
                    "/v1/messages",
                    null,
                    true,
                    false,
                    List.of("Anthropic Claude")),
            new ModelProtocolVO(
                    "ollama-native",
                    "Ollama 原生",
                    "Ollama 原生协议：POST {baseUrl}/api/chat 与 GET {baseUrl}/api/tags，本地模型免密钥。"
                            + "当前版本尚未实现；Ollama 同时提供 /v1 兼容端点，可改用 OpenAI 兼容协议接入。",
                    "/api/chat",
                    "/api/tags",
                    false,
                    false,
                    List.of("Ollama")));

    private final ModelProviderMapper modelProviderMapper;
    private final ModelProviderModelMapper modelProviderModelMapper;
    private final ProviderSecretCipher providerSecretCipher;
    private final OpenAiCompatibleClient openAiCompatibleClient;
    private final TransactionTemplate transactionTemplate;

    // ---------- read ----------

    @Override
    public List<ModelProviderVO> listProviders() {
        List<ModelProviderEntity> providers = modelProviderMapper.selectList(
                new LambdaQueryWrapper<ModelProviderEntity>()
                        .orderByDesc(ModelProviderEntity::getIsDefault)
                        .orderByAsc(ModelProviderEntity::getId));
        if (providers.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ModelProviderModelVO>> modelsByProvider =
                loadModels(providers.stream().map(ModelProviderEntity::getId).toList());
        return providers.stream()
                .map(entity -> ModelProviderVO.fromEntity(
                        entity, modelsByProvider.getOrDefault(entity.getId(), List.of())))
                .toList();
    }

    @Override
    public ModelProviderVO getProvider(Long id) {
        return toVO(findEntity(id));
    }

    @Override
    public ModelProviderVO resolveProvider(String providerId) {
        return toVO(findEntityByCode(providerId));
    }

    @Override
    public List<ModelProtocolVO> listProtocols() {
        return PROTOCOLS;
    }

    @Override
    public ModelEndpointTO resolveEndpoint(String providerId, String modelName) {
        ModelProviderEntity entity = findEntityByCode(providerId);
        if (STATUS_DISABLED.equals(entity.getStatus())) {
            throw new BusinessException(
                    "MODEL_PROVIDER_DISABLED",
                    "模型供应商已停用，无法发起调用。providerId=" + entity.getProviderCode());
        }
        return buildEndpoint(entity, modelName);
    }

    // ---------- write ----------

    @Override
    @Transactional
    @AuditOperation(action = "CREATE_MODEL_PROVIDER", targetType = TARGET_TYPE)
    public ModelProviderVO createProvider(ModelProviderCreateRequest request) {
        String providerCode = request.providerId().trim();
        String protocol = validateProtocol(request.protocol());
        ensureProviderCodeIsAvailable(providerCode);
        List<ModelProviderModelInput> models = normalizeModelInputs(request.models());

        String status = resolveStatus(request.enabled(), null);
        boolean makeDefault = Boolean.TRUE.equals(request.defaultProvider()) || !hasAnyProvider();
        if (makeDefault && STATUS_DISABLED.equals(status)) {
            throw new BusinessException(
                    "MODEL_PROVIDER_DEFAULT_DISABLED_CONFLICT",
                    "停用的供应商不能设为默认，请启用后再设为默认。providerId=" + providerCode);
        }

        ModelProviderEntity entity = new ModelProviderEntity();
        entity.setProviderCode(providerCode);
        entity.setProviderName(request.name().trim());
        entity.setProtocol(protocol);
        entity.setBaseUrl(normalizeBaseUrl(request.baseUrl(), request.chatCompletionsPath()));
        entity.setChatCompletionsPath(
                pathOrDefault(request.chatCompletionsPath(), OpenAiCompatibleClient.DEFAULT_CHAT_COMPLETIONS_PATH));
        entity.setModelsPath(pathOrDefault(request.modelsPath(), OpenAiCompatibleClient.DEFAULT_MODELS_PATH));
        entity.setTimeoutSeconds(request.timeoutSeconds() == null
                ? Integer.valueOf(OpenAiCompatibleClient.DEFAULT_TIMEOUT_SECONDS)
                : request.timeoutSeconds());
        entity.setTemperature(request.temperature());
        entity.setMaxTokens(request.maxTokens());
        entity.setDescription(normalizeOptionalText(request.description()));
        entity.setBuiltin(FLAG_FALSE);
        entity.setStatus(status);
        entity.setIsDefault(makeDefault ? FLAG_TRUE : FLAG_FALSE);
        entity.setVersion(FLAG_FALSE);
        if (StringUtils.hasText(request.apiKey())) {
            String apiKey = request.apiKey().trim();
            entity.setApiKeyCipher(providerSecretCipher.encrypt(apiKey));
            entity.setApiKeyHint(providerSecretCipher.mask(apiKey));
        }
        modelProviderMapper.insert(entity);

        if (makeDefault) {
            clearOtherDefaultProviders(entity.getId());
        }
        for (ModelProviderModelInput input : models) {
            insertModel(entity.getId(), input, Boolean.TRUE.equals(input.defaultModel()), SOURCE_MANUAL);
        }
        if (!models.isEmpty() && models.stream().noneMatch(model -> Boolean.TRUE.equals(model.defaultModel()))) {
            promoteFirstModelAsDefault(entity.getId());
        }

        log.atInfo()
                .addKeyValue("providerId", providerCode)
                .addKeyValue("protocol", protocol)
                .addKeyValue("modelCount", models.size())
                .addKeyValue("defaultProvider", makeDefault)
                .log("Model provider created");
        return toVO(entity);
    }

    @Override
    @Transactional
    @AuditOperation(action = "UPDATE_MODEL_PROVIDER", targetType = TARGET_TYPE)
    public ModelProviderVO updateProvider(Long id, ModelProviderUpdateRequest request) {
        ModelProviderEntity existing = findEntity(id);
        String protocol = validateProtocol(request.protocol());
        String status = resolveStatus(null, request.status());
        if (STATUS_DISABLED.equals(status) && FLAG_TRUE.equals(existing.getIsDefault())) {
            throw new BusinessException(
                    "MODEL_PROVIDER_DEFAULT_DISABLE_REJECTED",
                    "默认供应商不能停用，请先将其他供应商设为默认。providerId=" + existing.getProviderCode());
        }
        List<ModelProviderModelInput> models = request.models() == null
                ? null
                : normalizeModelInputs(request.models());

        OffsetDateTime now = OffsetDateTime.now();
        String currentUserId = CurrentUserContextHolder.currentOrAnonymous().userId();
        // Explicit column list so that cleared values are written as NULL, which updateById would skip.
        LambdaUpdateWrapper<ModelProviderEntity> wrapper = new LambdaUpdateWrapper<ModelProviderEntity>()
                .eq(ModelProviderEntity::getId, id)
                .eq(ModelProviderEntity::getDeleted, FLAG_FALSE)
                .eq(ModelProviderEntity::getVersion, request.version())
                .set(ModelProviderEntity::getProviderName, request.name().trim())
                .set(ModelProviderEntity::getProtocol, protocol)
                .set(ModelProviderEntity::getBaseUrl,
                        normalizeBaseUrl(request.baseUrl(), request.chatCompletionsPath()))
                .set(ModelProviderEntity::getChatCompletionsPath, pathOrDefault(request.chatCompletionsPath(),
                        OpenAiCompatibleClient.DEFAULT_CHAT_COMPLETIONS_PATH))
                .set(ModelProviderEntity::getModelsPath,
                        pathOrDefault(request.modelsPath(), OpenAiCompatibleClient.DEFAULT_MODELS_PATH))
                .set(ModelProviderEntity::getTimeoutSeconds, request.timeoutSeconds() == null
                        ? Integer.valueOf(OpenAiCompatibleClient.DEFAULT_TIMEOUT_SECONDS)
                        : request.timeoutSeconds())
                .set(ModelProviderEntity::getTemperature, request.temperature())
                .set(ModelProviderEntity::getMaxTokens, request.maxTokens())
                .set(ModelProviderEntity::getDescription, normalizeOptionalText(request.description()))
                .set(ModelProviderEntity::getStatus, status)
                .set(ModelProviderEntity::getVersion, request.version() + 1)
                .set(ModelProviderEntity::getUpdatedAt, now)
                .set(ModelProviderEntity::getUpdatedBy, currentUserId);
        if (StringUtils.hasText(request.apiKey())) {
            String apiKey = request.apiKey().trim();
            wrapper.set(ModelProviderEntity::getApiKeyCipher, providerSecretCipher.encrypt(apiKey))
                    .set(ModelProviderEntity::getApiKeyHint, providerSecretCipher.mask(apiKey));
        } else if (request.shouldClearApiKey()) {
            wrapper.set(ModelProviderEntity::getApiKeyCipher, null)
                    .set(ModelProviderEntity::getApiKeyHint, null);
        }
        requireSingleRow(modelProviderMapper.update(null, wrapper), "MODEL_PROVIDER_VERSION_CONFLICT", id);

        if (models != null) {
            syncModels(id, models);
        }

        log.atInfo()
                .addKeyValue("providerId", existing.getProviderCode())
                .addKeyValue("protocol", protocol)
                .addKeyValue("version", request.version() + 1)
                .log("Model provider updated");
        return toVO(modelProviderMapper.selectById(id));
    }

    @Override
    @Transactional
    @AuditOperation(action = "DELETE_MODEL_PROVIDER", targetType = TARGET_TYPE)
    public void deleteProvider(Long id, ModelProviderDeleteRequest request) {
        ModelProviderEntity existing = findEntity(id);
        if (FLAG_TRUE.equals(existing.getBuiltin())) {
            throw new BusinessException(
                    "MODEL_PROVIDER_BUILTIN_PROTECTED",
                    "内置供应商不可删除，可将其停用。providerId=" + existing.getProviderCode());
        }
        if (FLAG_TRUE.equals(existing.getIsDefault())) {
            throw new BusinessException(
                    "MODEL_PROVIDER_DEFAULT_DELETE_REJECTED",
                    "默认供应商不可删除，请先将其他供应商设为默认。providerId=" + existing.getProviderCode());
        }

        OffsetDateTime now = OffsetDateTime.now();
        String currentUserId = CurrentUserContextHolder.currentOrAnonymous().userId();
        requireSingleRow(modelProviderMapper.update(null, new LambdaUpdateWrapper<ModelProviderEntity>()
                .eq(ModelProviderEntity::getId, id)
                .eq(ModelProviderEntity::getDeleted, FLAG_FALSE)
                .eq(ModelProviderEntity::getVersion, request.version())
                .set(ModelProviderEntity::getDeleted, FLAG_TRUE)
                .set(ModelProviderEntity::getDeletedAt, now)
                .set(ModelProviderEntity::getDeletedBy, currentUserId)
                .set(ModelProviderEntity::getDeleteReason, request.reason().trim())
                .set(ModelProviderEntity::getUpdatedAt, now)
                .set(ModelProviderEntity::getUpdatedBy, currentUserId)), "MODEL_PROVIDER_VERSION_CONFLICT", id);

        // Models belong to the provider, so they are retired together with it.
        for (ModelProviderModelEntity model : listModelEntities(id)) {
            softDeleteModel(model, now, currentUserId, "供应商已删除");
        }

        log.atInfo()
                .addKeyValue("providerId", existing.getProviderCode())
                .log("Model provider deleted");
    }

    @Override
    @Transactional
    @AuditOperation(action = "UPDATE_MODEL_PROVIDER_STATUS", targetType = TARGET_TYPE)
    public ModelProviderVO toggleProviderStatus(Long id) {
        ModelProviderEntity existing = findEntity(id);
        String targetStatus = STATUS_ACTIVE.equals(existing.getStatus()) ? STATUS_DISABLED : STATUS_ACTIVE;
        if (STATUS_DISABLED.equals(targetStatus) && FLAG_TRUE.equals(existing.getIsDefault())) {
            throw new BusinessException(
                    "MODEL_PROVIDER_DEFAULT_DISABLE_REJECTED",
                    "默认供应商不能停用，请先将其他供应商设为默认。providerId=" + existing.getProviderCode());
        }
        requireSingleRow(modelProviderMapper.update(null, new LambdaUpdateWrapper<ModelProviderEntity>()
                        .eq(ModelProviderEntity::getId, id)
                        .eq(ModelProviderEntity::getDeleted, FLAG_FALSE)
                        .eq(ModelProviderEntity::getVersion, existing.getVersion())
                        .set(ModelProviderEntity::getStatus, targetStatus)
                        .set(ModelProviderEntity::getVersion, existing.getVersion() + 1)
                        .set(ModelProviderEntity::getUpdatedAt, OffsetDateTime.now())
                        .set(ModelProviderEntity::getUpdatedBy,
                                CurrentUserContextHolder.currentOrAnonymous().userId())),
                "MODEL_PROVIDER_VERSION_CONFLICT", id);

        log.atInfo()
                .addKeyValue("providerId", existing.getProviderCode())
                .addKeyValue("status", targetStatus)
                .log("Model provider status toggled");
        return toVO(modelProviderMapper.selectById(id));
    }

    @Override
    @Transactional
    @AuditOperation(action = "SET_DEFAULT_MODEL_PROVIDER", targetType = TARGET_TYPE)
    public ModelProviderVO setDefaultProvider(Long id) {
        ModelProviderEntity existing = findEntity(id);
        if (STATUS_DISABLED.equals(existing.getStatus())) {
            throw new BusinessException(
                    "MODEL_PROVIDER_DISABLED_DEFAULT_REJECTED",
                    "已停用的供应商不能设为默认，请先启用。providerId=" + existing.getProviderCode());
        }
        if (FLAG_TRUE.equals(existing.getIsDefault())) {
            return toVO(existing);
        }
        OffsetDateTime now = OffsetDateTime.now();
        String currentUserId = CurrentUserContextHolder.currentOrAnonymous().userId();
        modelProviderMapper.update(null, new LambdaUpdateWrapper<ModelProviderEntity>()
                .eq(ModelProviderEntity::getIsDefault, FLAG_TRUE)
                .eq(ModelProviderEntity::getDeleted, FLAG_FALSE)
                .ne(ModelProviderEntity::getId, id)
                .set(ModelProviderEntity::getIsDefault, FLAG_FALSE)
                .set(ModelProviderEntity::getUpdatedAt, now)
                .set(ModelProviderEntity::getUpdatedBy, currentUserId));
        requireSingleRow(modelProviderMapper.update(null, new LambdaUpdateWrapper<ModelProviderEntity>()
                        .eq(ModelProviderEntity::getId, id)
                        .eq(ModelProviderEntity::getDeleted, FLAG_FALSE)
                        .eq(ModelProviderEntity::getVersion, existing.getVersion())
                        .set(ModelProviderEntity::getIsDefault, FLAG_TRUE)
                        .set(ModelProviderEntity::getVersion, existing.getVersion() + 1)
                        .set(ModelProviderEntity::getUpdatedAt, now)
                        .set(ModelProviderEntity::getUpdatedBy, currentUserId)),
                "MODEL_PROVIDER_VERSION_CONFLICT", id);

        log.atInfo()
                .addKeyValue("providerId", existing.getProviderCode())
                .log("Default model provider changed");
        return toVO(modelProviderMapper.selectById(id));
    }

    @Override
    @Transactional
    @AuditOperation(action = "SET_DEFAULT_MODEL_PROVIDER_MODEL", targetType = TARGET_TYPE)
    public ModelProviderVO setDefaultModel(Long providerId, Long modelId) {
        ModelProviderEntity provider = findEntity(providerId);
        ModelProviderModelEntity model = modelProviderModelMapper.selectById(modelId);
        if (model == null || !provider.getId().equals(model.getProviderId())) {
            throw new BusinessException(
                    "MODEL_PROVIDER_MODEL_NOT_FOUND",
                    "Model was not found under this provider. providerId=" + provider.getProviderCode()
                            + ", modelId=" + modelId,
                    HttpStatus.NOT_FOUND);
        }
        if (STATUS_DISABLED.equals(model.getStatus())) {
            throw new BusinessException(
                    "MODEL_PROVIDER_MODEL_DISABLED_DEFAULT_REJECTED",
                    "已停用的模型不能设为默认，请先启用。model=" + model.getModelName());
        }
        if (!FLAG_TRUE.equals(model.getIsDefault())) {
            OffsetDateTime now = OffsetDateTime.now();
            String currentUserId = CurrentUserContextHolder.currentOrAnonymous().userId();
            modelProviderModelMapper.update(null, new LambdaUpdateWrapper<ModelProviderModelEntity>()
                    .eq(ModelProviderModelEntity::getProviderId, provider.getId())
                    .eq(ModelProviderModelEntity::getIsDefault, FLAG_TRUE)
                    .eq(ModelProviderModelEntity::getDeleted, FLAG_FALSE)
                    .set(ModelProviderModelEntity::getIsDefault, FLAG_FALSE)
                    .set(ModelProviderModelEntity::getUpdatedAt, now)
                    .set(ModelProviderModelEntity::getUpdatedBy, currentUserId));
            requireSingleRow(modelProviderModelMapper.update(null, new LambdaUpdateWrapper<ModelProviderModelEntity>()
                            .eq(ModelProviderModelEntity::getId, modelId)
                            .eq(ModelProviderModelEntity::getDeleted, FLAG_FALSE)
                            .eq(ModelProviderModelEntity::getVersion, model.getVersion())
                            .set(ModelProviderModelEntity::getIsDefault, FLAG_TRUE)
                            .set(ModelProviderModelEntity::getVersion, model.getVersion() + 1)
                            .set(ModelProviderModelEntity::getUpdatedAt, now)
                            .set(ModelProviderModelEntity::getUpdatedBy, currentUserId)),
                    "MODEL_PROVIDER_MODEL_VERSION_CONFLICT", modelId);
        }

        log.atInfo()
                .addKeyValue("providerId", provider.getProviderCode())
                .addKeyValue("modelName", model.getModelName())
                .log("Default model changed");
        return toVO(modelProviderMapper.selectById(provider.getId()));
    }

    // ---------- probing ----------

    @Override
    @AuditOperation(action = "TEST_MODEL_PROVIDER", targetType = TARGET_TYPE)
    public ModelProviderTestResponse testProvider(ModelProviderTestRequest request) {
        ModelProviderEntity entity = findEntityByCode(request.providerId());
        ModelProviderVO provider = toVO(entity);

        ProviderProbeTO outcome;
        String testedModel = null;
        if (!OpenAiCompatibleClient.PROTOCOL.equals(entity.getProtocol())) {
            outcome = ProviderProbeTO.failure(
                    PROBE_NOT_READY,
                    null,
                    0L,
                    "协议 " + entity.getProtocol() + " 暂不支持连通性测试，当前仅支持 "
                            + OpenAiCompatibleClient.PROTOCOL);
        } else {
            ModelEndpointTO endpoint = buildEndpoint(entity, request.modelName());
            testedModel = endpoint.modelName();
            outcome = openAiCompatibleClient.listModels(endpoint);
            if (outcome.success() && request.shouldProbeChat()) {
                outcome = StringUtils.hasText(testedModel)
                        ? openAiCompatibleClient.probeChat(endpoint, testedModel)
                        : ProviderProbeTO.failure(
                                ProviderProbeTO.STATUS_CLIENT_ERROR,
                                outcome.httpStatus(),
                                outcome.latencyMs(),
                                "服务可达，但该供应商尚未配置模型，无法完成对话验证");
            }
        }

        persistProbeResult(entity.getId(), outcome);
        log.atInfo()
                .addKeyValue("providerId", entity.getProviderCode())
                .addKeyValue("status", outcome.status())
                .addKeyValue("latencyMs", outcome.latencyMs())
                .log("Model provider connectivity tested");
        return toTestResponse(entity, provider, outcome, testedModel);
    }

    @Override
    @AuditOperation(action = "DISCOVER_MODEL_PROVIDER_MODELS", targetType = TARGET_TYPE)
    public ModelProviderDiscoveryVO discoverModels(Long id, ModelProviderDiscoveryRequest request) {
        ModelProviderEntity entity = findEntity(id);
        if (!OpenAiCompatibleClient.PROTOCOL.equals(entity.getProtocol())) {
            throw new BusinessException(
                    "MODEL_PROVIDER_PROTOCOL_UNSUPPORTED",
                    "协议 " + entity.getProtocol() + " 暂不支持模型发现，当前仅支持 "
                            + OpenAiCompatibleClient.PROTOCOL);
        }
        ProviderProbeTO probe = openAiCompatibleClient.listModels(buildEndpoint(entity, null));
        persistProbeResult(entity.getId(), probe);
        if (!probe.success()) {
            throw new BusinessException("MODEL_PROVIDER_DISCOVERY_FAILED", probe.message());
        }

        List<String> discovered = probe.models();
        boolean persist = request != null && request.shouldPersist();
        int persistedCount = persist
                ? transactionTemplate.execute(status -> persistDiscoveredModels(entity.getId(), discovered))
                : Integer.valueOf(0);
        int skipped = discovered.size() - persistedCount;

        log.atInfo()
                .addKeyValue("providerId", entity.getProviderCode())
                .addKeyValue("discovered", discovered.size())
                .addKeyValue("persisted", persistedCount)
                .log("Model provider catalogue discovered");
        return new ModelProviderDiscoveryVO(
                entity.getProviderCode(),
                discovered,
                persistedCount,
                Math.max(skipped, 0),
                persist
                        ? "已发现 " + discovered.size() + " 个模型，新增入库 " + persistedCount + " 个"
                        : "已发现 " + discovered.size() + " 个模型（未入库）");
    }

    // ---------- endpoint resolution ----------

    private ModelEndpointTO buildEndpoint(ModelProviderEntity entity, String requestedModel) {
        if (!OpenAiCompatibleClient.PROTOCOL.equals(entity.getProtocol())) {
            throw new BusinessException(
                    "MODEL_PROVIDER_PROTOCOL_UNSUPPORTED",
                    "暂不支持的接入协议：" + entity.getProtocol() + "。当前仅支持 "
                            + OpenAiCompatibleClient.PROTOCOL + "（OpenAI 兼容协议）。");
        }
        ModelProviderModelEntity model = resolveModelEntity(entity.getId(), requestedModel);
        String modelName = StringUtils.hasText(requestedModel)
                ? requestedModel.trim()
                : (model == null ? null : model.getModelName());
        BigDecimal temperature = model != null && model.getTemperature() != null
                ? model.getTemperature()
                : entity.getTemperature();
        Integer maxTokens = model != null && model.getMaxTokens() != null
                ? model.getMaxTokens()
                : entity.getMaxTokens();
        return new ModelEndpointTO(
                entity.getProviderCode(),
                entity.getProviderName(),
                entity.getProtocol(),
                entity.getBaseUrl(),
                pathOrDefault(entity.getChatCompletionsPath(), OpenAiCompatibleClient.DEFAULT_CHAT_COMPLETIONS_PATH),
                pathOrDefault(entity.getModelsPath(), OpenAiCompatibleClient.DEFAULT_MODELS_PATH),
                providerSecretCipher.decrypt(entity.getApiKeyCipher()),
                OpenAiCompatibleClient.clampTimeout(entity.getTimeoutSeconds()),
                modelName,
                temperature,
                maxTokens);
    }

    private ModelProviderModelEntity resolveModelEntity(Long providerId, String requestedModel) {
        if (StringUtils.hasText(requestedModel)) {
            return modelProviderModelMapper.selectOne(new LambdaQueryWrapper<ModelProviderModelEntity>()
                    .eq(ModelProviderModelEntity::getProviderId, providerId)
                    .eq(ModelProviderModelEntity::getModelName, requestedModel.trim())
                    .eq(ModelProviderModelEntity::getDeleted, FLAG_FALSE)
                    .last("limit 1"));
        }
        ModelProviderModelEntity defaultModel = modelProviderModelMapper.selectOne(
                new LambdaQueryWrapper<ModelProviderModelEntity>()
                        .eq(ModelProviderModelEntity::getProviderId, providerId)
                        .eq(ModelProviderModelEntity::getIsDefault, FLAG_TRUE)
                        .eq(ModelProviderModelEntity::getDeleted, FLAG_FALSE)
                        .orderByAsc(ModelProviderModelEntity::getId)
                        .last("limit 1"));
        if (defaultModel != null) {
            return defaultModel;
        }
        return modelProviderModelMapper.selectOne(new LambdaQueryWrapper<ModelProviderModelEntity>()
                .eq(ModelProviderModelEntity::getProviderId, providerId)
                .eq(ModelProviderModelEntity::getStatus, STATUS_ACTIVE)
                .eq(ModelProviderModelEntity::getDeleted, FLAG_FALSE)
                .orderByAsc(ModelProviderModelEntity::getId)
                .last("limit 1"));
    }

    // ---------- model maintenance ----------

    private void syncModels(Long providerId, List<ModelProviderModelInput> inputs) {
        List<ModelProviderModelEntity> existing = listModelEntities(providerId);
        Map<Long, ModelProviderModelEntity> byId = existing.stream()
                .collect(Collectors.toMap(ModelProviderModelEntity::getId, Function.identity()));
        Map<String, ModelProviderModelEntity> byName = existing.stream()
                .collect(Collectors.toMap(ModelProviderModelEntity::getModelName, Function.identity(),
                        (first, second) -> first));

        Set<Long> retained = new HashSet<>();
        boolean defaultAssigned = false;
        for (ModelProviderModelInput input : inputs) {
            String modelName = input.modelName().trim();
            boolean wantDefault = Boolean.TRUE.equals(input.defaultModel());
            ModelProviderModelEntity target = input.id() == null ? null : byId.get(input.id());
            if (target == null) {
                target = byName.get(modelName);
            }
            if (target == null) {
                insertModel(providerId, input, wantDefault, SOURCE_MANUAL);
            } else {
                retained.add(target.getId());
                updateModel(target, input, modelName, wantDefault);
            }
            defaultAssigned = defaultAssigned || wantDefault;
        }

        OffsetDateTime now = OffsetDateTime.now();
        String currentUserId = CurrentUserContextHolder.currentOrAnonymous().userId();
        for (ModelProviderModelEntity entity : existing) {
            if (!retained.contains(entity.getId())) {
                softDeleteModel(entity, now, currentUserId, "供应商模型配置已更新");
            }
        }
        if (!defaultAssigned) {
            promoteFirstModelAsDefault(providerId);
        }
    }

    private void insertModel(Long providerId, ModelProviderModelInput input, boolean wantDefault, String source) {
        ModelProviderModelEntity entity = new ModelProviderModelEntity();
        entity.setProviderId(providerId);
        entity.setModelName(input.modelName().trim());
        entity.setDisplayName(normalizeOptionalText(input.displayName()));
        entity.setTemperature(input.temperature());
        entity.setMaxTokens(input.maxTokens());
        entity.setIsDefault(wantDefault ? FLAG_TRUE : FLAG_FALSE);
        entity.setSource(source);
        entity.setCapability(normalizeCapability(input.capability()));
        entity.setStatus(StringUtils.hasText(input.status()) ? input.status() : STATUS_ACTIVE);
        entity.setRemark(normalizeOptionalText(input.remark()));
        entity.setVersion(FLAG_FALSE);
        modelProviderModelMapper.insert(entity);
    }

    private void updateModel(ModelProviderModelEntity target, ModelProviderModelInput input,
                             String modelName, boolean wantDefault) {
        requireSingleRow(modelProviderModelMapper.update(null, new LambdaUpdateWrapper<ModelProviderModelEntity>()
                        .eq(ModelProviderModelEntity::getId, target.getId())
                        .eq(ModelProviderModelEntity::getDeleted, FLAG_FALSE)
                        .eq(ModelProviderModelEntity::getVersion, target.getVersion())
                        .set(ModelProviderModelEntity::getModelName, modelName)
                        .set(ModelProviderModelEntity::getDisplayName, normalizeOptionalText(input.displayName()))
                        .set(ModelProviderModelEntity::getTemperature, input.temperature())
                        .set(ModelProviderModelEntity::getMaxTokens, input.maxTokens())
                        .set(ModelProviderModelEntity::getIsDefault, wantDefault ? FLAG_TRUE : FLAG_FALSE)
                        .set(ModelProviderModelEntity::getStatus,
                                StringUtils.hasText(input.status()) ? input.status() : target.getStatus())
                        .set(ModelProviderModelEntity::getCapability,
                                normalizeCapability(StringUtils.hasText(input.capability())
                                        ? input.capability()
                                        : target.getCapability()))
                        .set(ModelProviderModelEntity::getRemark, normalizeOptionalText(input.remark()))
                        .set(ModelProviderModelEntity::getVersion, target.getVersion() + 1)
                        .set(ModelProviderModelEntity::getUpdatedAt, OffsetDateTime.now())
                        .set(ModelProviderModelEntity::getUpdatedBy,
                                CurrentUserContextHolder.currentOrAnonymous().userId())),
                "MODEL_PROVIDER_MODEL_VERSION_CONFLICT", target.getId());
    }

    private void softDeleteModel(ModelProviderModelEntity entity, OffsetDateTime now, String userId, String reason) {
        modelProviderModelMapper.update(null, new LambdaUpdateWrapper<ModelProviderModelEntity>()
                .eq(ModelProviderModelEntity::getId, entity.getId())
                .eq(ModelProviderModelEntity::getDeleted, FLAG_FALSE)
                .set(ModelProviderModelEntity::getDeleted, FLAG_TRUE)
                .set(ModelProviderModelEntity::getDeletedAt, now)
                .set(ModelProviderModelEntity::getDeletedBy, userId)
                .set(ModelProviderModelEntity::getDeleteReason, reason)
                .set(ModelProviderModelEntity::getUpdatedAt, now)
                .set(ModelProviderModelEntity::getUpdatedBy, userId));
    }

    private int persistDiscoveredModels(Long providerId, List<String> discovered) {
        Set<String> known = listModelEntities(providerId).stream()
                .map(ModelProviderModelEntity::getModelName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int inserted = 0;
        for (String modelName : discovered) {
            if (!known.add(modelName)) {
                continue;
            }
            insertModel(providerId, new ModelProviderModelInput(
                    null, modelName, null, null, null, Boolean.FALSE, STATUS_ACTIVE, null,
                    "由接口自动发现"), false, SOURCE_DISCOVERED);
            inserted++;
        }
        if (inserted > 0 && known.size() == inserted) {
            promoteFirstModelAsDefault(providerId);
        }
        return inserted;
    }

    private void promoteFirstModelAsDefault(Long providerId) {
        boolean hasDefault = modelProviderModelMapper.selectCount(new LambdaQueryWrapper<ModelProviderModelEntity>()
                .eq(ModelProviderModelEntity::getProviderId, providerId)
                .eq(ModelProviderModelEntity::getIsDefault, FLAG_TRUE)
                .eq(ModelProviderModelEntity::getDeleted, FLAG_FALSE)) > 0;
        if (hasDefault) {
            return;
        }
        ModelProviderModelEntity candidate = modelProviderModelMapper.selectOne(
                new LambdaQueryWrapper<ModelProviderModelEntity>()
                        .eq(ModelProviderModelEntity::getProviderId, providerId)
                        .eq(ModelProviderModelEntity::getStatus, STATUS_ACTIVE)
                        .eq(ModelProviderModelEntity::getDeleted, FLAG_FALSE)
                        .orderByAsc(ModelProviderModelEntity::getId)
                        .last("limit 1"));
        if (candidate == null) {
            return;
        }
        modelProviderModelMapper.update(null, new LambdaUpdateWrapper<ModelProviderModelEntity>()
                .eq(ModelProviderModelEntity::getId, candidate.getId())
                .eq(ModelProviderModelEntity::getDeleted, FLAG_FALSE)
                .set(ModelProviderModelEntity::getIsDefault, FLAG_TRUE)
                .set(ModelProviderModelEntity::getUpdatedAt, OffsetDateTime.now())
                .set(ModelProviderModelEntity::getUpdatedBy, CurrentUserContextHolder.currentOrAnonymous().userId()));
    }

    // ---------- helpers ----------

    private ModelProviderVO toVO(ModelProviderEntity entity) {
        List<ModelProviderModelVO> models =
                loadModels(List.of(entity.getId())).getOrDefault(entity.getId(), List.of());
        return ModelProviderVO.fromEntity(entity, models);
    }

    private Map<Long, List<ModelProviderModelVO>> loadModels(Collection<Long> providerIds) {
        if (providerIds.isEmpty()) {
            return Map.of();
        }
        List<ModelProviderModelEntity> models = modelProviderModelMapper.selectList(
                new LambdaQueryWrapper<ModelProviderModelEntity>()
                        .in(ModelProviderModelEntity::getProviderId, providerIds)
                        .orderByDesc(ModelProviderModelEntity::getIsDefault)
                        .orderByAsc(ModelProviderModelEntity::getId));
        return models.stream().collect(Collectors.groupingBy(
                ModelProviderModelEntity::getProviderId,
                LinkedHashMap::new,
                Collectors.mapping(ModelProviderModelVO::fromEntity, Collectors.toList())));
    }

    private List<ModelProviderModelEntity> listModelEntities(Long providerId) {
        return modelProviderModelMapper.selectList(new LambdaQueryWrapper<ModelProviderModelEntity>()
                .eq(ModelProviderModelEntity::getProviderId, providerId)
                .orderByAsc(ModelProviderModelEntity::getId));
    }

    private ModelProviderEntity findEntity(Long id) {
        ModelProviderEntity entity = id == null ? null : modelProviderMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(
                    "MODEL_PROVIDER_NOT_FOUND",
                    "Model provider was not found. id=" + id,
                    HttpStatus.NOT_FOUND);
        }
        return entity;
    }

    private ModelProviderEntity findEntityByCode(String providerId) {
        String providerCode = StringUtils.hasText(providerId) ? providerId.trim() : null;
        ModelProviderEntity entity = providerCode == null
                ? findDefaultEntity()
                : modelProviderMapper.selectOne(new LambdaQueryWrapper<ModelProviderEntity>()
                        .eq(ModelProviderEntity::getProviderCode, providerCode)
                        .eq(ModelProviderEntity::getDeleted, FLAG_FALSE)
                        .last("limit 1"));
        if (entity == null) {
            throw new BusinessException(
                    "MODEL_PROVIDER_NOT_FOUND",
                    providerCode == null
                            ? "尚未配置任何模型供应商，请先在「模型供应商」页面新增。"
                            : "Model provider not found: " + providerCode,
                    HttpStatus.NOT_FOUND);
        }
        return entity;
    }

    private ModelProviderEntity findDefaultEntity() {
        ModelProviderEntity defaultProvider = modelProviderMapper.selectOne(
                new LambdaQueryWrapper<ModelProviderEntity>()
                        .eq(ModelProviderEntity::getIsDefault, FLAG_TRUE)
                        .eq(ModelProviderEntity::getDeleted, FLAG_FALSE)
                        .orderByAsc(ModelProviderEntity::getId)
                        .last("limit 1"));
        if (defaultProvider != null) {
            return defaultProvider;
        }
        return modelProviderMapper.selectOne(new LambdaQueryWrapper<ModelProviderEntity>()
                .eq(ModelProviderEntity::getDeleted, FLAG_FALSE)
                .orderByAsc(ModelProviderEntity::getId)
                .last("limit 1"));
    }

    private boolean hasAnyProvider() {
        return modelProviderMapper.selectCount(new LambdaQueryWrapper<ModelProviderEntity>()
                .eq(ModelProviderEntity::getDeleted, FLAG_FALSE)) > 0;
    }

    private void ensureProviderCodeIsAvailable(String providerCode) {
        Long count = modelProviderMapper.selectCount(new LambdaQueryWrapper<ModelProviderEntity>()
                .eq(ModelProviderEntity::getProviderCode, providerCode)
                .eq(ModelProviderEntity::getDeleted, FLAG_FALSE));
        if (count > 0) {
            throw new BusinessException(
                    "MODEL_PROVIDER_CODE_EXISTS",
                    "供应商 ID 已存在：" + providerCode,
                    HttpStatus.CONFLICT);
        }
    }

    private void clearOtherDefaultProviders(Long keepId) {
        modelProviderMapper.update(null, new LambdaUpdateWrapper<ModelProviderEntity>()
                .eq(ModelProviderEntity::getIsDefault, FLAG_TRUE)
                .eq(ModelProviderEntity::getDeleted, FLAG_FALSE)
                .ne(ModelProviderEntity::getId, keepId)
                .set(ModelProviderEntity::getIsDefault, FLAG_FALSE)
                .set(ModelProviderEntity::getUpdatedAt, OffsetDateTime.now())
                .set(ModelProviderEntity::getUpdatedBy, CurrentUserContextHolder.currentOrAnonymous().userId()));
    }

    private void persistProbeResult(Long id, ProviderProbeTO probe) {
        modelProviderMapper.update(null, new LambdaUpdateWrapper<ModelProviderEntity>()
                .eq(ModelProviderEntity::getId, id)
                .eq(ModelProviderEntity::getDeleted, FLAG_FALSE)
                .set(ModelProviderEntity::getLastTestStatus, probe.status())
                .set(ModelProviderEntity::getLastTestHttpStatus, probe.httpStatus())
                .set(ModelProviderEntity::getLastTestMessage, truncate(probe.message(), 1000))
                .set(ModelProviderEntity::getLastTestLatencyMs, Long.valueOf(probe.latencyMs()))
                .set(ModelProviderEntity::getLastTestAt, OffsetDateTime.now())
                .set(ModelProviderEntity::getUpdatedAt, OffsetDateTime.now())
                .set(ModelProviderEntity::getUpdatedBy, CurrentUserContextHolder.currentOrAnonymous().userId()));
    }

    private ModelProviderTestResponse toTestResponse(ModelProviderEntity entity, ModelProviderVO provider,
                                                     ProviderProbeTO probe, String testedModel) {
        ModelProviderTestResultVO persisted = provider.lastTest();
        return new ModelProviderTestResponse(
                entity.getProviderCode(),
                provider.apiKeyConfigured(),
                provider.enabled(),
                probe.status(),
                probe.message(),
                probe.success(),
                probe.httpStatus(),
                Long.valueOf(probe.latencyMs()),
                testedModel,
                probe.models(),
                persisted == null ? OffsetDateTime.now() : persisted.testedAt());
    }

    private List<ModelProviderModelInput> normalizeModelInputs(List<ModelProviderModelInput> models) {
        if (models == null || models.isEmpty()) {
            return List.of();
        }
        if (models.size() > MAX_MODELS_PER_PROVIDER) {
            throw new BusinessException(
                    "MODEL_PROVIDER_MODEL_LIMIT_EXCEEDED",
                    "单个供应商最多配置 " + MAX_MODELS_PER_PROVIDER + " 个模型，当前提交了 " + models.size() + " 个");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<ModelProviderModelInput> normalized = new ArrayList<>(models.size());
        boolean defaultAssigned = false;
        for (ModelProviderModelInput input : models) {
            String modelName = input.modelName().trim();
            if (!seen.add(modelName)) {
                throw new BusinessException("MODEL_PROVIDER_MODEL_DUPLICATED", "模型名称重复：" + modelName);
            }
            if (Boolean.TRUE.equals(input.defaultModel())) {
                if (defaultAssigned) {
                    throw new BusinessException(
                            "MODEL_PROVIDER_MODEL_DEFAULT_DUPLICATED",
                            "一个供应商只能指定一个默认模型");
                }
                defaultAssigned = true;
            }
            normalized.add(input);
        }
        return List.copyOf(normalized);
    }

    private String validateProtocol(String protocol) {
        String normalized = protocol == null ? "" : protocol.trim().toLowerCase(Locale.ROOT);
        if (!OpenAiCompatibleClient.PROTOCOL.equals(normalized)) {
            throw new BusinessException(
                    "MODEL_PROVIDER_PROTOCOL_UNSUPPORTED",
                    "暂不支持的接入协议：" + protocol + "。当前仅支持 " + OpenAiCompatibleClient.PROTOCOL
                            + "（OpenAI 兼容协议）。");
        }
        return normalized;
    }

    /**
     * Normalizes a base URL to the service root, because the request paths already carry the version
     * segment. Vendor docs often quote {@code https://host/v1}, which would otherwise produce
     * {@code /v1/v1/chat/completions}.
     */
    private String normalizeBaseUrl(String baseUrl, String chatCompletionsPath) {
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (StringUtils.hasText(chatCompletionsPath)) {
            return normalized;
        }
        String withoutVersion = normalized.endsWith("/v1")
                ? normalized.substring(0, normalized.length() - "/v1".length())
                : normalized;
        // Keep the stripped value only when a host is still present, so that a path-bearing root such
        // as https://host/compatible-mode survives while a bare https://v1 does not.
        if (!withoutVersion.equals(normalized) && withoutVersion.matches("^https?://[^/]+(/.*)?")) {
            log.atInfo()
                    .addKeyValue("original", normalized)
                    .addKeyValue("normalized", withoutVersion)
                    .log("Stripped the trailing /v1 from a model provider base URL");
            return withoutVersion;
        }
        return normalized;
    }

    private String pathOrDefault(String path, String fallback) {
        return StringUtils.hasText(path) ? path.trim() : fallback;
    }

    private String resolveStatus(Boolean enabled, String explicitStatus) {
        if (StringUtils.hasText(explicitStatus)) {
            return explicitStatus;
        }
        if (enabled == null) {
            return STATUS_ACTIVE;
        }
        return enabled.booleanValue() ? STATUS_ACTIVE : STATUS_DISABLED;
    }

    private String normalizeOptionalText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * Capability whitelist for model rows. Unknown or missing values fall back to TEXT so a model is
     * never silently treated as vision-capable (which would route receipt images to it).
     */
    private String normalizeCapability(String value) {
        if (!StringUtils.hasText(value)) {
            return "TEXT";
        }
        String candidate = value.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (candidate) {
            case "VISION", "EMBEDDING" -> candidate;
            default -> "TEXT";
        };
    }

    private void requireSingleRow(int updated, String errorCode, Long id) {
        if (updated != 1) {
            throw new BusinessException(
                    errorCode,
                    "配置已被其他请求修改，请刷新后重试。id=" + id,
                    HttpStatus.CONFLICT);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
