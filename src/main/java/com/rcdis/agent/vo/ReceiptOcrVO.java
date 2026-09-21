package com.rcdis.agent.vo;

import java.math.BigDecimal;

/** Client view of one receipt OCR extraction result. */
public record ReceiptOcrVO(
        String fileName,
        String status,
        String docType,
        String fieldsJson,
        BigDecimal confidence,
        String providerCode,
        String modelName,
        String errorMessage
) {
}
