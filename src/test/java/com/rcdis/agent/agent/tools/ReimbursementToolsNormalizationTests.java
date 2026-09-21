package com.rcdis.agent.agent.tools;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.rcdis.agent.dto.ReimbursementItemInput;

import static org.assertj.core.api.Assertions.assertThat;

/** Placeholder normalization keeps absent optional fields absent instead of failing validation. */
class ReimbursementToolsNormalizationTests {

    private ReimbursementItemInput item(String vendor, String invoiceNo, String receiptFile, String account) {
        return new ReimbursementItemInput(
                new BigDecimal("587.00"), LocalDate.of(2026, 9, 20),
                vendor, invoiceNo, receiptFile, "消费", account);
    }

    @Test
    void placeholderReceiptFileBecomesNullSoValidationIsSkipped() {
        List<ReimbursementItemInput> normalized = ReimbursementTools.normalizeItems(
                List.of(item("N/A", "N/A", "N/A", "张三")));

        assertThat(normalized).singleElement().satisfies(item -> {
            assertThat(item.receiptFile()).isNull();
            assertThat(item.vendor()).isNull();
            assertThat(item.invoiceNo()).isNull();
            assertThat(item.counterpartyAccount()).isEqualTo("张三");
            assertThat(item.description()).isEqualTo("消费");
            assertThat(item.amount()).isEqualByComparingTo("587.00");
        });
    }

    @Test
    void genuineValuesAndRealReceiptReferencesAreKept() {
        String receipt = "/api/files/receipts/14006905-48b2-4655-9e94-79fb7e55a835.png/content";
        List<ReimbursementItemInput> normalized = ReimbursementTools.normalizeItems(
                List.of(item("_vendor_", "INV-1", receipt, null)));

        assertThat(normalized).singleElement().satisfies(item -> {
            assertThat(item.vendor()).isEqualTo("_vendor_");
            assertThat(item.invoiceNo()).isEqualTo("INV-1");
            assertThat(item.receiptFile()).isEqualTo(receipt);
        });
    }

    @Test
    void placeholderMatchingIsCaseInsensitiveAndTrimsWhitespace() {
        List<ReimbursementItemInput> normalized = ReimbursementTools.normalizeItems(
                List.of(item(" n/A ", "无", "  ", "-")));

        assertThat(normalized).singleElement().satisfies(item -> {
            assertThat(item.vendor()).isNull();
            assertThat(item.invoiceNo()).isNull();
            assertThat(item.receiptFile()).isNull();
            assertThat(item.counterpartyAccount()).isNull();
        });
    }
}
