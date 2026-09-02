package com.rcdis.agent.service;

import java.util.List;

import com.rcdis.agent.dto.ModelProviderCreateRequest;
import com.rcdis.agent.dto.ModelProviderDeleteRequest;
import com.rcdis.agent.dto.ModelProviderDiscoveryRequest;
import com.rcdis.agent.dto.ModelProviderTestRequest;
import com.rcdis.agent.dto.ModelProviderTestResponse;
import com.rcdis.agent.dto.ModelProviderUpdateRequest;
import com.rcdis.agent.to.ModelEndpointTO;
import com.rcdis.agent.vo.ModelProtocolVO;
import com.rcdis.agent.vo.ModelProviderDiscoveryVO;
import com.rcdis.agent.vo.ModelProviderVO;

/**
 * Model provider registry backed by PostgreSQL.
 *
 * <p>{@code application.yml} only seeds the table on first startup; afterwards this service is the
 * single source of truth for provider metadata, models, and encrypted API keys.</p>
 */
public interface ModelProviderService {

    List<ModelProviderVO> listProviders();

    ModelProviderVO getProvider(Long id);

    /**
     * Resolves a provider by code, falling back to the default provider when the code is blank.
     */
    ModelProviderVO resolveProvider(String providerId);

    ModelProviderVO createProvider(ModelProviderCreateRequest request);

    ModelProviderVO updateProvider(Long id, ModelProviderUpdateRequest request);

    void deleteProvider(Long id, ModelProviderDeleteRequest request);

    ModelProviderVO toggleProviderStatus(Long id);

    ModelProviderVO setDefaultProvider(Long id);

    ModelProviderVO setDefaultModel(Long providerId, Long modelId);

    /**
     * Probes the real endpoint and persists the outcome on the provider row.
     */
    ModelProviderTestResponse testProvider(ModelProviderTestRequest request);

    /**
     * Pulls the model catalogue from the endpoint, optionally persisting unknown models.
     */
    ModelProviderDiscoveryVO discoverModels(Long id, ModelProviderDiscoveryRequest request);

    /**
     * Wire protocols a custom endpoint may implement, for display in the provider form.
     */
    List<ModelProtocolVO> listProtocols();

    /**
     * Resolves connection details including the decrypted API key, for infrastructure clients only.
     *
     * @param modelName optional model override; the provider default is used when blank
     */
    ModelEndpointTO resolveEndpoint(String providerId, String modelName);
}
