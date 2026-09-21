package com.rcdis.agent.vo;

/**
 * One input parameter of an Agent tool, extracted from the tool's JSON schema.
 */
public record AgentToolParamVO(
        String name,
        String type,
        boolean required,
        String description
) {
}
