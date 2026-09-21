package com.rcdis.agent.to;

/**
 * One entry in a turn's ordered tool-call chain.
 */
public record ToolCallTraceTO(
        String name,
        boolean ok,
        long durationMs,
        String status
) {
}
