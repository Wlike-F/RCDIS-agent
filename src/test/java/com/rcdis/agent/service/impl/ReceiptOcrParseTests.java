package com.rcdis.agent.service.impl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.rcdis.agent.entity.ReimbursementItemEntity;
import com.rcdis.agent.service.impl.ReceiptOcrServiceImpl.ParsedReceipt;
import com.rcdis.agent.vo.MaterialCheckVO;

/** Deterministic parsing and comparison of vision-model receipt extraction output. */
class ReceiptOcrParseTests {

    @Test
    void parsesFencedChineseWechatOutputIntoCanonicalFields() {
        String raw = """
                ```json
                {"doc_type":"WECHAT_PAY","fields":{"交易单号":"4200001234567890","金额":"¥1,234.50","收款方":"张三"},"confidence":0.92}
                ```
                """;
        ParsedReceipt parsed = ReceiptOcrServiceImpl.parseModelOutput(raw);

        assertThat(parsed).isNotNull();
        assertThat(parsed.docType()).isEqualTo("WECHAT_PAY");
        assertThat(parsed.fields()).containsEntry("pay_no", "4200001234567890")
                .containsEntry("amount", "1234.50")
                .containsEntry("counterparty", "张三");
        assertThat(parsed.confidence()).isEqualByComparingTo("0.92");
    }

    @Test
    void parsesEnglishInvoiceOutputAndNormalizesAmount() {
        String raw = """
                {"doc_type":"invoice","fields":{"invoice_no":"INV-2026-001","invoice_date":"2026-09-18","total_amount":"￥587.00元","seller_name":"某某科技有限公司"},"confidence":1.5}
                """;
        ParsedReceipt parsed = ReceiptOcrServiceImpl.parseModelOutput(raw);

        assertThat(parsed.docType()).isEqualTo("INVOICE");
        assertThat(parsed.fields()).containsEntry("invoice_no", "INV-2026-001")
                .containsEntry("total_amount", "587.00");
        assertThat(parsed.confidence()).isEqualByComparingTo("1");
    }

    @Test
    void garbageOutputYieldsNullAndUnknownDocTypeFallsBack() {
        assertThat(ReceiptOcrServiceImpl.parseModelOutput("抱歉，我无法识别这张图片。")).isNull();
        assertThat(ReceiptOcrServiceImpl.parseModelOutput(null)).isNull();
        ParsedReceipt unknown = ReceiptOcrServiceImpl.parseModelOutput("{\"doc_type\":\"其他\",\"fields\":{}}");
        assertThat(unknown.docType()).isEqualTo("UNKNOWN");
        assertThat(unknown.fields()).isEmpty();
    }

    @Test
    void extractsFileNameFromReferenceAndBareName() {
        assertThat(ReceiptOcrServiceImpl.extractFileName("/api/files/receipts/abc.png/content")).isEqualTo("abc.png");
        assertThat(ReceiptOcrServiceImpl.extractFileName("abc.png")).isEqualTo("abc.png");
        assertThat(ReceiptOcrServiceImpl.extractFileName("junk-value")).isNull();
        assertThat(ReceiptOcrServiceImpl.extractFileName(null)).isNull();
    }

    @Test
    void parsesTextLayerPdfInvoiceIntoCanonicalFields() {
        String text = """
                电子发票（普通发票）\n发票号码：25317000001234567890\n开票日期：2026年09月18日\n\
                购买方信息　名称：某某实验室　统一社会信用代码：...\n\
                项目名称*信息技术服务费*金额100.00\n\
                合计￥450.00元　（大写）肆佰伍拾元整（小写）￥450.00\n\
                销售方信息　名称：某某科技有限公司　统一社会信用代码：...
                """;
        ParsedReceipt parsed = ReceiptOcrServiceImpl.parseInvoiceText(text);

        assertThat(parsed).isNotNull();
        assertThat(parsed.docType()).isEqualTo("INVOICE");
        assertThat(parsed.fields())
                .containsEntry("invoice_no", "25317000001234567890")
                .containsEntry("invoice_date", "2026-09-18")
                .containsEntry("total_amount", "450.00")
                .containsEntry("seller_name", "某某科技有限公司");
    }

    @Test
    void pdfTextWithoutInvoiceAnchorsYieldsNull() {
        assertThat(ReceiptOcrServiceImpl.parseInvoiceText("这是一份普通报告，没有发票字段。")).isNull();
        assertThat(ReceiptOcrServiceImpl.parseInvoiceText(null)).isNull();
    }

    @Test
    void mismatchingAmountAndInvoiceNumberProduceWarningsOnly() {
        ReimbursementItemEntity item = new ReimbursementItemEntity();
        item.setId(7L);
        item.setAmount(new BigDecimal("587.00"));
        item.setInvoiceNo("INV-OTHER");
        ParsedReceipt parsed = new ParsedReceipt("INVOICE",
                Map.of("invoice_no", "INV-2026-001", "total_amount", "587.00"), new BigDecimal("0.9"));

        List<MaterialCheckVO.Finding> findings =
                ReceiptOcrServiceImpl.mismatches(item.getId(), item.getAmount(), item.getInvoiceNo(), parsed);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.level()).isEqualTo("warning");
            assertThat(finding.message()).contains("发票号");
        });
    }

    @Test
    void consistentFieldsAndMissingOcrFieldsProduceNoFindings() {
        ReimbursementItemEntity item = new ReimbursementItemEntity();
        item.setId(7L);
        item.setAmount(new BigDecimal("587.00"));
        item.setInvoiceNo(null);
        ParsedReceipt consistent = new ParsedReceipt("INVOICE",
                Map.of("invoice_no", "INV-2026-001", "total_amount", "587.00"), BigDecimal.ONE);
        assertThat(ReceiptOcrServiceImpl.mismatches(7L, item.getAmount(), null, consistent)).isEmpty();

        // Screenshot without a recognizable number: only an amount mismatch would fire.
        ParsedReceipt payScreenshot = new ParsedReceipt("WECHAT_PAY", Map.of("amount", "587.00"), null);
        assertThat(ReceiptOcrServiceImpl.mismatches(7L, item.getAmount(), null, payScreenshot)).isEmpty();
    }
}
