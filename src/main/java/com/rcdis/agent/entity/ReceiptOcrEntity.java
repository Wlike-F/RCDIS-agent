package com.rcdis.agent.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Structured OCR extraction result for one uploaded receipt image.
 *
 * <p>Written asynchronously by the vision-model extraction pipeline right after the receipt is
 * uploaded; the raw model output is kept for audit even when parsing fails. Keyed by the
 * {@code uploaded_file.file_name} so reimbursement lines can look the result up through their
 * {@code receiptFile} reference.</p>
 */
@Getter
@Setter
@TableName("receipt_ocr")
public class ReceiptOcrEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    public static final String DOC_INVOICE = "INVOICE";
    public static final String DOC_WECHAT_PAY = "WECHAT_PAY";
    public static final String DOC_ALIPAY_PAY = "ALIPAY_PAY";
    public static final String DOC_UNKNOWN = "UNKNOWN";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String fileName;
    private String status;
    private String docType;
    private String fieldsJson;
    private BigDecimal confidence;
    private String rawResponse;
    private String providerCode;
    private String modelName;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
