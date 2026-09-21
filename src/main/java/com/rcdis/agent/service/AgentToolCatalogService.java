package com.rcdis.agent.service;

import java.util.List;

import com.rcdis.agent.vo.AgentToolVO;

/**
 * Read-only catalogue of the Agent tools currently registered on the ChatClient.
 *
 * <p>Backs the developer console so operators can see exactly which tools the model may invoke,
 * their parameters, and whether they are read-only or confirmation-gated, without reading code.</p>
 */
public interface AgentToolCatalogService {

    List<AgentToolVO> listTools();

    /** Runtime-derived compact catalogue appended to the system prompt. */
    String promptCatalogue();
}
