package com.rcdis.agent.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Updates the current user's semantic-memory switches (read/write separated).
 */
public record MemorySettingUpdateRequest(
        @NotNull Boolean extractEnabled,
        @NotNull Boolean injectEnabled
) {
}
