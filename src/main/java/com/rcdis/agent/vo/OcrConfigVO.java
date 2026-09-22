package com.rcdis.agent.vo;

import java.util.List;

/** Admin-facing receipt OCR configuration: current selection plus the provider/model options. */
public record OcrConfigVO(
        boolean enabled,
        String providerId,
        String model,
        List<ModelProviderVO> providers
) {
}
