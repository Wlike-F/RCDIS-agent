package com.rcdis.agent;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.rcdis.agent.entity.ReceiptOcrEntity;
import com.rcdis.agent.entity.ReimbursementItemEntity;
import com.rcdis.agent.entity.UploadedFileEntity;
import com.rcdis.agent.mapper.ReceiptOcrMapper;
import com.rcdis.agent.mapper.UploadedFileMapper;
import com.rcdis.agent.service.ReceiptOcrService;
import com.rcdis.agent.vo.MaterialCheckVO;
import com.rcdis.agent.vo.ReceiptOcrVO;

/**
 * OCR-to-reimbursement association: a persisted DONE recognition row produces WARNING findings
 * for contradicting item fields and stays silent for consistent or unrecognized receipts.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "rcdis.feishu.enabled=false",
        "rcdis.feishu.client-type=noop"
})
class AgentReceiptOcrComparisonTests {

    @Autowired
    private ReceiptOcrService receiptOcrService;

    @Autowired
    private ReceiptOcrMapper receiptOcrMapper;

    @Autowired
    private UploadedFileMapper uploadedFileMapper;

    @Test
    void doneRowWithContradictingAmountProducesWarningFinding() {
        String fileName = seedReceiptFile();
        seedOcrRow(fileName, ReceiptOcrEntity.STATUS_DONE, ReceiptOcrEntity.DOC_INVOICE,
                "{\"invoice_no\":\"INV-2026-001\",\"total_amount\":\"100.00\"}");

        ReceiptOcrVO vo = receiptOcrService.getByFileName(fileName);
        assertThat(vo).isNotNull();
        assertThat(vo.status()).isEqualTo(ReceiptOcrEntity.STATUS_DONE);
        // The canonical URL form is accepted for lookup as well.
        assertThat(receiptOcrService.getByFileName("/api/files/receipts/" + fileName + "/content")).isNotNull();

        ReimbursementItemEntity item = item(9L, fileName, new BigDecimal("587.00"), "INV-2026-001");
        List<MaterialCheckVO.Finding> findings = receiptOcrService.compareWithItem(item);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.level()).isEqualTo("warning");
            assertThat(finding.itemId()).isEqualTo(9L);
            assertThat(finding.message()).contains("凭证识别金额 100.00");
        });
    }

    @Test
    void consistentOrMissingRecognitionProducesNoFindings() {
        String fileName = seedReceiptFile();
        seedOcrRow(fileName, ReceiptOcrEntity.STATUS_DONE, ReceiptOcrEntity.DOC_INVOICE,
                "{\"invoice_no\":\"INV-2026-001\",\"total_amount\":\"587.00\"}");

        assertThat(receiptOcrService.compareWithItem(
                item(10L, fileName, new BigDecimal("587.00"), "INV-2026-001"))).isEmpty();

        // FAILED rows and unknown files never produce findings (no noise from missing OCR data).
        String failedFile = seedReceiptFile();
        seedOcrRow(failedFile, ReceiptOcrEntity.STATUS_FAILED, null, null);
        assertThat(receiptOcrService.compareWithItem(
                item(11L, failedFile, new BigDecimal("1.00"), null))).isEmpty();
        assertThat(receiptOcrService.compareWithItem(item(12L, "not-uploaded.png", BigDecimal.ONE, null))).isEmpty();
        assertThat(receiptOcrService.getByFileName("not-uploaded.png")).isNull();
    }

    private String seedReceiptFile() {
        String fileName = UUID.randomUUID() + ".png";
        UploadedFileEntity file = new UploadedFileEntity();
        file.setOwnerUserId("u-ocr");
        file.setCategory("RECEIPT");
        file.setFileName(fileName);
        file.setStoredPath("uploads/receipts/" + fileName);
        file.setMime("image/png");
        file.setSizeBytes(4L);
        file.setCreatedBy("u-ocr");
        uploadedFileMapper.insert(file);
        return fileName;
    }

    private void seedOcrRow(String fileName, String status, String docType, String fieldsJson) {
        ReceiptOcrEntity row = new ReceiptOcrEntity();
        row.setFileName(fileName);
        row.setStatus(status);
        row.setDocType(docType);
        row.setFieldsJson(fieldsJson);
        row.setCreatedAt(OffsetDateTime.now());
        row.setUpdatedAt(OffsetDateTime.now());
        receiptOcrMapper.insert(row);
    }

    private ReimbursementItemEntity item(Long id, String receiptFile, BigDecimal amount, String invoiceNo) {
        ReimbursementItemEntity item = new ReimbursementItemEntity();
        item.setId(id);
        item.setReceiptFile("/api/files/receipts/" + receiptFile + "/content");
        item.setAmount(amount);
        item.setInvoiceNo(invoiceNo);
        return item;
    }
}
