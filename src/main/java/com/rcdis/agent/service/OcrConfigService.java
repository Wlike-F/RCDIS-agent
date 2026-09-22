package com.rcdis.agent.service;

import com.rcdis.agent.vo.OcrConfigVO;

/**
 * Runtime configuration for receipt OCR recognition. Values chosen by the admin are persisted in
 * {@code app_setting} and take effect immediately; {@code application.yml} only holds the defaults.
 */
public interface OcrConfigService {

    /** Current OCR configuration plus the selectable provider/model options. */
    OcrConfigVO current();

    /**
     * Updates the OCR configuration (null fields keep the current value), validates that the
     * provider exists, persists it and returns the new configuration.
     */
    OcrConfigVO update(Boolean enabled, String providerId, String model);
}
