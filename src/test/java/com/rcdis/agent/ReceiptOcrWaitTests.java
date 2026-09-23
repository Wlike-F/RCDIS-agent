package com.rcdis.agent;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rcdis.agent.entity.ReceiptOcrEntity;
import com.rcdis.agent.mapper.ReceiptOcrMapper;
import com.rcdis.agent.service.ReceiptOcrService;
import com.rcdis.agent.vo.ReceiptOcrVO;

/**
 * The get_receipt_ocr tool must be able to block until an asynchronous recognition finishes, so a
 * freshly registered receipt returns its fields in the same conversation turn.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "rcdis.feishu.enabled=false",
        "rcdis.feishu.client-type=noop"
})
class ReceiptOcrWaitTests {

    @Autowired
    private ReceiptOcrService receiptOcrService;

    @Autowired
    private ReceiptOcrMapper receiptOcrMapper;

    @Test
    void waitsUntilRecognitionReachesTerminalStatus() throws Exception {
        String fileName = "wait-test-" + UUID_SUFFIX + "-a.png";
        ReceiptOcrEntity pending = new ReceiptOcrEntity();
        pending.setFileName(fileName);
        pending.setStatus(ReceiptOcrEntity.STATUS_PENDING);
        pending.setCreatedAt(OffsetDateTime.now());
        pending.setUpdatedAt(OffsetDateTime.now());
        receiptOcrMapper.insert(pending);

        ScheduledExecutorService finisher = Executors.newSingleThreadScheduledExecutor();
        finisher.schedule(() -> {
            ReceiptOcrEntity row = receiptOcrMapper.selectOne(new LambdaQueryWrapper<ReceiptOcrEntity>()
                    .eq(ReceiptOcrEntity::getFileName, fileName)
                    .last("LIMIT 1"));
            row.setStatus(ReceiptOcrEntity.STATUS_DONE);
            row.setDocType("INVOICE");
            row.setFieldsJson("{\"invoice_no\":\"26100000020099\",\"total_amount\":\"99.00\"}");
            row.setConfidence(new BigDecimal("0.95"));
            row.setUpdatedAt(OffsetDateTime.now());
            receiptOcrMapper.updateById(row);
        }, 1, TimeUnit.SECONDS);

        try {
            ReceiptOcrVO result = receiptOcrService.waitForResult(fileName, Duration.ofSeconds(8));
            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(ReceiptOcrEntity.STATUS_DONE);
            assertThat(result.fieldsJson()).contains("26100000020099");
        } finally {
            finisher.shutdownNow();
        }
    }

    @Test
    void returnsNullWhenTimeoutElapsesWithoutTerminalStatus() {
        ReceiptOcrVO result = receiptOcrService.waitForResult(
                "wait-test-never-" + UUID_SUFFIX + ".png", Duration.ofSeconds(2));
        assertThat(result).isNull();
    }

    private static final String UUID_SUFFIX = java.util.UUID.randomUUID().toString().substring(0, 8);
}
