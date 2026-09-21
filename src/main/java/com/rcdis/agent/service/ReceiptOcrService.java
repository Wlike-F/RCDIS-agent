package com.rcdis.agent.service;

import java.util.List;

import com.rcdis.agent.entity.ReimbursementItemEntity;
import com.rcdis.agent.vo.MaterialCheckVO;
import com.rcdis.agent.vo.ReceiptOcrVO;

/**
 * Async vision-model recognition of uploaded receipt images plus deterministic comparison of the
 * recognized fields against reimbursement item lines.
 */
public interface ReceiptOcrService {

    /**
     * Asynchronously recognizes a stored receipt image (canonical receiptFile URL or file name)
     * and persists the structured result. Never throws to the caller; failures land in the
     * {@code receipt_ocr} row with status FAILED.
     */
    void recognizeAsync(String receiptFileReference);

    /** Latest recognition result for a receipt file reference, or null when none exists. */
    ReceiptOcrVO getByFileName(String fileName);

    /**
     * WARNING-level findings where a reimbursement item contradicts the recognized receipt fields.
     * Only fires when both sides carry the compared field; missing OCR data produces no noise.
     */
    List<MaterialCheckVO.Finding> compareWithItem(ReimbursementItemEntity item);
}
