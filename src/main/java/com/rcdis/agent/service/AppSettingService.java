package com.rcdis.agent.service;

/** Runtime key/value settings backed by the {@code app_setting} table. */
public interface AppSettingService {

    /** Raw value for a key, or null when unset. */
    String get(String key);

    /** Upserts a value; blank values are stored as-is so callers can clear overrides. */
    void put(String key, String value);
}
