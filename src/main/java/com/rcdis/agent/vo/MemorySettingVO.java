package com.rcdis.agent.vo;

/**
 * Effective semantic-memory switches for the current user, exposing both the user-level and the
 * global (config) level so the UI can show why a switch is disabled.
 */
public record MemorySettingVO(
        String userId,
        boolean extractEnabled,
        boolean injectEnabled,
        boolean globalExtractEnabled,
        boolean globalInjectEnabled
) {
}
