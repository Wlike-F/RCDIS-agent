package com.rcdis.agent.dto;

/**
 * Pulls the model catalogue from a provider endpoint.
 *
 * <p>With {@code persist} the models that are not registered yet are inserted as discovered,
 * enabled, non-default rows so that the user can prune them afterwards.</p>
 */
public record ModelProviderDiscoveryRequest(
        Boolean persist
) {

    public boolean shouldPersist() {
        return Boolean.TRUE.equals(persist);
    }
}
