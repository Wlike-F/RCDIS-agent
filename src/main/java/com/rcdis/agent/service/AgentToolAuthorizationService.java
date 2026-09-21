package com.rcdis.agent.service;

import com.rcdis.agent.agent.AgentToolContext;

/** Enforces hard authorization before a model-selected tool reaches its implementation. */
public interface AgentToolAuthorizationService {

    void authorize(String toolName, String toolInput, AgentToolContext context);
}
