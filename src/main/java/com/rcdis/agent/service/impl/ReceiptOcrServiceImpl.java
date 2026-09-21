package com.rcdis.agent.service.impl;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.entity.ReceiptOcrEntity;
import com.rcdis.agent.entity.ReimbursementItemEntity;
import com.rcdis.agent.entity.UploadedFileEntity;
import com.rcdis.agent.infrastructure.ai.ChatModelFactory;
import com.rcdis.agent.infrastructure.document.DocumentTextExtractor;
import com.rcdis.agent.mapper.ReceiptOcrMapper;
import com.rcdis.agent.mapper.UploadedFileMapper;
import com.rcdis.agent.service.FileStorageService;
import com.rcdis.agent.service.ModelProviderService;
import com.rcdis.agent.service.ReceiptOcrService;
import com.rcdis.agent.to.ModelEndpointTO;
import com.rcdis.agent.vo.MaterialCheckVO;
import com.rcdis.agent.vo.ReceiptOcrVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Vision-model extraction of structured fields from uploaded receipt images, plus deterministic
 * comparison of the recognized fields against reimbursement item lines.
 *
 * <p>The model is not the source of truth: its raw output is persisted for audit, normalization
 * and comparison are pure code, and every failure degrades to a FAILED row without ever affecting
 * the upload or the reimbursement flow.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptOcrServiceImpl implements ReceiptOcrService {

    static final String EXTRACTION_PROMPT = """
            识别这张图片是哪类凭证并抽取字段。严格只输出如下 JSON，不要输出任何其他文字、解释或 Markdown 代码块：
            {"doc_type":"INVOICE 或 WECHAT_PAY 或 ALIPAY_PAY 或 UNKNOWN","fields":{...},"confidence":0.0}
            fields 可用键（缺失的键直接省略，值一律为字符串）：
            - INVOICE（增值税发票）：invoice_no 发票号码, invoice_date 开票日期(YYYY-MM-DD), total_amount 价税合计(纯数字), seller_name 销售方名称
            - WECHAT_PAY（微信支付截图）：pay_no 支付单号/交易单号, pay_time 支付时间(YYYY-MM-DD HH:mm:ss), amount 金额(纯数字), counterparty 收款方名称
            - ALIPAY_PAY（支付宝截图）：同 WECHAT_PAY
            - UNKNOWN：fields 为空对象
            confidence 为 0 到 1 的识别置信度。""";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*\\}", Pattern.DOTALL);
    private static final Pattern AMOUNT_NOISE = Pattern.compile("[^0-9.]");
    private static final Pattern RECEIPT_URL = Pattern.compile("^/api/files/receipts/([^/]+)/content$");
    private static final String FINDING_WARNING = "warning";

    /** Chinese and English field-name aliases mapped to canonical storage keys. */
    private static final Map<String, String> FIELD_ALIASES = Map.ofEntries(
            Map.entry("发票号码", "invoice_no"), Map.entry("invoice_no", "invoice_no"),
            Map.entry("开票日期", "invoice_date"), Map.entry("invoice_date", "invoice_date"),
            Map.entry("价税合计", "total_amount"), Map.entry("合计金额", "total_amount"),
            Map.entry("total_amount", "total_amount"),
            Map.entry("销售方", "seller_name"), Map.entry("销售方名称", "seller_name"),
            Map.entry("seller_name", "seller_name"),
            Map.entry("支付单号", "pay_no"), Map.entry("交易单号", "pay_no"), Map.entry("pay_no", "pay_no"),
            Map.entry("支付时间", "pay_time"), Map.entry("pay_time", "pay_time"),
            Map.entry("金额", "amount"), Map.entry("amount", "amount"),
            Map.entry("收款方", "counterparty"), Map.entry("收款方名称", "counterparty"),
            Map.entry("counterparty", "counterparty"));

    private final ReceiptOcrMapper receiptOcrMapper;
    private final UploadedFileMapper uploadedFileMapper;
    private final FileStorageService fileStorageService;
    private final ModelProviderService modelProviderService;
    private final ChatModelFactory chatModelFactory;
    private final DocumentTextExtractor documentTextExtractor;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;

    @Override
    @Async("ocrTaskExecutor")
    public void recognizeAsync(String receiptFileReference) {
        String fileName = extractFileName(receiptFileReference);
        if (!agentProperties.getOcr().isEnabled() || fileName == null) {
            return;
        }
        ReceiptOcrEntity row = startRow(fileName);
        try {
            UploadedFileEntity file = findReceiptFile(fileName);
            if (file == null) {
                finishSkipped(row, "Receipt file not found: " + fileName);
                return;
            }
            Path content = fileStorageService.contentPath(file);
            ParsedReceipt parsed;
            String raw;
            if (fileName.endsWith(".pdf")) {
                // Text-layer e-invoice: deterministic extraction, no vision model involved.
                String text = documentTextExtractor.extract(content, "PDF");
                if (!StringUtils.hasText(text)) {
                    finishFailed(row, null, "PDF 未抽取到文本内容，可能是扫描件；请改为上传图片凭证");
                    return;
                }
                raw = text;
                parsed = parseInvoiceText(text);
            } else {
                byte[] image = Files.readAllBytes(content);
                raw = callVisionModel(file, image);
                parsed = parseModelOutput(raw);
            }
            if (parsed == null) {
                finishFailed(row, raw, fileName.endsWith(".pdf")
                        ? "PDF 文本中未解析出可识别的发票字段，请人工核对填写"
                        : "无法从模型输出中解析出 JSON");
                return;
            }
            row.setStatus(ReceiptOcrEntity.STATUS_DONE);
            row.setDocType(parsed.docType());
            row.setFieldsJson(MAPPER.writeValueAsString(parsed.fields()));
            row.setConfidence(parsed.confidence());
            row.setRawResponse(raw);
            if (fileName.endsWith(".pdf")) {
                row.setProviderCode("builtin");
                row.setModelName("pdf-text-extraction");
            } else {
                row.setProviderCode(agentProperties.getOcr().getProviderId());
                row.setModelName(agentProperties.getOcr().getModel());
            }
            row.setErrorMessage(null);
            saveRow(row);
            log.atInfo()
                    .addKeyValue("fileName", fileName)
                    .addKeyValue("docType", parsed.docType())
                    .log("Receipt OCR recognized");
        } catch (BusinessException exception) {
            // Unresolvable provider/config problems are a setup issue, not a recognition failure.
            if (exception.getCode() != null && exception.getCode().startsWith("MODEL_PROVIDER")) {
                finishSkipped(row, exception.getMessage());
            } else {
                finishFailed(row, null, exception.getMessage());
            }
        } catch (Exception exception) {
            finishFailed(row, null, exception.getMessage());
        }
    }

    @Override
    public ReceiptOcrVO getByFileName(String fileNameOrReference) {
        String fileName = extractFileName(fileNameOrReference);
        if (fileName == null) {
            return null;
        }
        ReceiptOcrEntity row = findRow(fileName);
        return row == null ? null : new ReceiptOcrVO(row.getFileName(), row.getStatus(), row.getDocType(),
                row.getFieldsJson(), row.getConfidence(), row.getProviderCode(), row.getModelName(),
                row.getErrorMessage());
    }

    @Override
    public List<MaterialCheckVO.Finding> compareWithItem(ReimbursementItemEntity item) {
        if (item == null || !StringUtils.hasText(item.getReceiptFile())) {
            return List.of();
        }
        String fileName = extractFileName(item.getReceiptFile());
        if (fileName == null) {
            return List.of();
        }
        ReceiptOcrEntity row = findRow(fileName);
        if (row == null || !ReceiptOcrEntity.STATUS_DONE.equals(row.getStatus())
                || !StringUtils.hasText(row.getFieldsJson())) {
            return List.of();
        }
        try {
            JsonNode node = MAPPER.readTree(row.getFieldsJson());
            Map<String, String> fields = new TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue().asText()));
            return mismatches(item.getId(), item.getAmount(), item.getInvoiceNo(),
                    new ParsedReceipt(row.getDocType(), fields, row.getConfidence()));
        } catch (Exception exception) {
            log.atWarn().setCause(exception)
                    .addKeyValue("fileName", row.getFileName())
                    .log("Failed to parse stored OCR fields; skipping comparison");
            return List.of();
        }
    }

    // ---------- recognition internals ----------

    private String callVisionModel(UploadedFileEntity file, byte[] image) {
        AgentProperties.Ocr ocr = agentProperties.getOcr();
        ModelEndpointTO endpoint = modelProviderService.resolveEndpoint(ocr.getProviderId(), ocr.getModel());
        OpenAiChatModel model = chatModelFactory.create(endpoint);
        // This Spring AI version takes multimodal input as a resource-only user message;
        // the extraction instruction therefore lives in the system message.
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("你是票据识别引擎，只输出 JSON。" + EXTRACTION_PROMPT),
                new UserMessage(new ByteArrayResource(image))));
        ChatResponse response = model.call(prompt);
        String text = response.getResult() == null || response.getResult().getOutput() == null
                ? null
                : response.getResult().getOutput().getText();
        if (!StringUtils.hasText(text)) {
            throw new BusinessException("OCR_EMPTY_RESPONSE", "视觉模型返回了空结果", HttpStatus.BAD_GATEWAY);
        }
        return text;
    }

    private static MimeType toMimeType(String mime) {
        try {
            return MimeType.valueOf(mime == null || mime.isBlank() ? "image/png" : mime);
        } catch (Exception exception) {
            return MimeType.valueOf("image/png");
        }
    }

    // ---------- deterministic parsing (pure, unit-testable) ----------

    /** Normalized extraction result; fields use canonical English keys. */
    record ParsedReceipt(String docType, Map<String, String> fields, BigDecimal confidence) {
    }

    /** Pure extraction of the model output into normalized fields; null when no JSON is found. */
    static ParsedReceipt parseModelOutput(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = JSON_BLOCK.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(matcher.group());
        } catch (Exception exception) {
            return null;
        }
        Map<String, String> fields = new TreeMap<>();
        JsonNode fieldsNode = root.path("fields");
        if (fieldsNode.isObject()) {
            fieldsNode.fields().forEachRemaining(entry -> {
                String canonical = FIELD_ALIASES.get(entry.getKey().trim());
                String value = normalizeFieldValue(canonical, entry.getValue().asText());
                if (canonical != null && StringUtils.hasText(value)) {
                    fields.put(canonical, value);
                }
            });
        }
        return new ParsedReceipt(
                normalizeDocType(root.path("doc_type").asText(null)), fields, normalizeConfidence(root.path("confidence")));
    }

    /** Pure comparison core: only fires when both sides carry the compared field. */
    static List<MaterialCheckVO.Finding> mismatches(
            Long itemId, BigDecimal itemAmount, String itemInvoiceNo, ParsedReceipt parsed) {
        List<MaterialCheckVO.Finding> findings = new ArrayList<>();
        if (parsed == null) {
            return findings;
        }
        String label = "明细 #" + itemId;
        String ocrAmountText = parsed.fields().containsKey("total_amount")
                ? parsed.fields().get("total_amount")
                : parsed.fields().get("amount");
        BigDecimal ocrAmount = parseAmount(ocrAmountText);
        if (ocrAmount != null && itemAmount != null && ocrAmount.compareTo(itemAmount) != 0) {
            findings.add(new MaterialCheckVO.Finding(FINDING_WARNING, itemId, label,
                    "凭证识别金额 " + ocrAmountText + " 与明细金额 " + itemAmount.toPlainString() + " 不一致"));
        }
        boolean invoice = ReceiptOcrEntity.DOC_INVOICE.equals(parsed.docType());
        String ocrNo = parsed.fields().get(invoice ? "invoice_no" : "pay_no");
        if (ocrNo != null && StringUtils.hasText(itemInvoiceNo)
                && !ocrNo.equalsIgnoreCase(itemInvoiceNo.trim())) {
            findings.add(new MaterialCheckVO.Finding(FINDING_WARNING, itemId, label,
                    "凭证识别" + (invoice ? "发票号 " : "支付单号 ") + ocrNo
                            + " 与明细填写 " + itemInvoiceNo.trim() + " 不一致"));
        }
        return findings;
    }

    static String normalizeDocType(String raw) {
        if (raw == null) {
            return ReceiptOcrEntity.DOC_UNKNOWN;
        }
        String value = raw.trim().toUpperCase();
        if (value.contains("WECHAT") || value.contains("微信")) {
            return ReceiptOcrEntity.DOC_WECHAT_PAY;
        }
        if (value.contains("ALIPAY") || value.contains("支付宝")) {
            return ReceiptOcrEntity.DOC_ALIPAY_PAY;
        }
        if (value.contains("INVOICE") || value.contains("发票")) {
            return ReceiptOcrEntity.DOC_INVOICE;
        }
        return ReceiptOcrEntity.DOC_UNKNOWN;
    }

    // ---------- text-layer PDF e-invoice parsing (pure, unit-testable) ----------

    private static final Pattern PDF_INVOICE_NO =
            Pattern.compile("发\\s*票\\s*号\\s*码\\s*[:：]?\\s*([0-9]{8,20})");
    private static final Pattern PDF_INVOICE_DATE = Pattern.compile(
            "开票日期\\s*[:：]?\\s*(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");
    private static final Pattern PDF_TOTAL_LOWER =
            Pattern.compile("[（(]\\s*小写\\s*[)）]\\s*[¥￥]?\\s*([0-9][0-9,]*\\.[0-9]{2})");
    private static final Pattern PDF_PARTY_NAME =
            Pattern.compile("名\\s*称\\s*[:：]\\s*([^\\n\\r/\\\\]{2,80})");

    /**
     * Deterministic extraction from a text-layer PDF e-invoice (数电票/电子普通发票). Heuristic:
     * the seller name is the last 「名称：」 match, since the buyer block precedes it in both the
     * classic and the fully-digitized layouts. Returns null when no invoice field is found.
     */
    static ParsedReceipt parseInvoiceText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Map<String, String> fields = new TreeMap<>();
        Matcher no = PDF_INVOICE_NO.matcher(text);
        if (no.find()) {
            fields.put("invoice_no", no.group(1));
        }
        Matcher date = PDF_INVOICE_DATE.matcher(text);
        if (date.find()) {
            fields.put("invoice_date", String.format("%s-%02d-%02d",
                    date.group(1), Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3))));
        }
        Matcher total = PDF_TOTAL_LOWER.matcher(text);
        if (total.find()) {
            fields.put("total_amount", total.group(1).replace(",", ""));
        }
        String seller = null;
        Matcher party = PDF_PARTY_NAME.matcher(text);
        while (party.find()) {
            seller = cleanPartyName(party.group(1));
        }
        if (StringUtils.hasText(seller)) {
            fields.put("seller_name", seller);
        }
        if (fields.isEmpty()) {
            return null;
        }
        return new ParsedReceipt(ReceiptOcrEntity.DOC_INVOICE, fields, null);
    }

    /** Cuts the captured 「名称：」value at the tax-id anchor or layout spacing left by PDF extraction. */
    private static String cleanPartyName(String value) {
        String cleaned = value.replaceAll("(统一社会信用代码|纳税人识别号).*$", "");
        cleaned = cleaned.replaceAll("[　\\s]{2,}.*$", "").strip();
        return cleaned.isBlank() ? null : cleaned;
    }

    private static String normalizeFieldValue(String canonicalKey, String value) {
        String trimmed = value == null ? "" : value.trim();
        if ("total_amount".equals(canonicalKey) || "amount".equals(canonicalKey)) {
            String digits = AMOUNT_NOISE.matcher(trimmed).replaceAll("");
            return digits.isEmpty() ? null : digits;
        }
        return trimmed;
    }

    private static BigDecimal parseAmount(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(AMOUNT_NOISE.matcher(text).replaceAll(""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static BigDecimal normalizeConfidence(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        BigDecimal value = node.decimalValue();
        if (value.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }

    // ---------- persistence helpers ----------

    private ReceiptOcrEntity startRow(String fileName) {
        ReceiptOcrEntity row = findRow(fileName);
        if (row == null) {
            row = new ReceiptOcrEntity();
            row.setFileName(fileName);
        }
        row.setStatus(ReceiptOcrEntity.STATUS_PENDING);
        saveRow(row);
        return row;
    }

    private void finishSkipped(ReceiptOcrEntity row, String reason) {
        row.setStatus(ReceiptOcrEntity.STATUS_SKIPPED);
        row.setErrorMessage(reason);
        saveRow(row);
        log.atWarn().addKeyValue("fileName", row.getFileName()).log("Receipt OCR skipped: {}", reason);
    }

    private void finishFailed(ReceiptOcrEntity row, String raw, String message) {
        row.setStatus(ReceiptOcrEntity.STATUS_FAILED);
        row.setErrorMessage(message);
        if (raw != null) {
            row.setRawResponse(raw);
        }
        saveRow(row);
        log.atError().addKeyValue("fileName", row.getFileName()).log("Receipt OCR failed: {}", message);
    }

    private void saveRow(ReceiptOcrEntity row) {
        row.setUpdatedAt(java.time.OffsetDateTime.now());
        if (row.getId() == null) {
            row.setCreatedAt(java.time.OffsetDateTime.now());
            receiptOcrMapper.insert(row);
        } else {
            receiptOcrMapper.updateById(row);
        }
    }

    private ReceiptOcrEntity findRow(String fileName) {
        return receiptOcrMapper.selectOne(new LambdaQueryWrapper<ReceiptOcrEntity>()
                .eq(ReceiptOcrEntity::getFileName, fileName)
                .last("LIMIT 1"));
    }

    private UploadedFileEntity findReceiptFile(String fileName) {
        return uploadedFileMapper.selectOne(new LambdaQueryWrapper<UploadedFileEntity>()
                .eq(UploadedFileEntity::getCategory, "RECEIPT")
                .eq(UploadedFileEntity::getFileName, fileName)
                .last("LIMIT 1"));
    }

    /** Accepts the canonical receipt URL, a bare file name, or null. */
    static String extractFileName(String receiptFileReference) {
        if (!StringUtils.hasText(receiptFileReference)) {
            return null;
        }
        String trimmed = receiptFileReference.trim();
        Matcher url = RECEIPT_URL.matcher(trimmed);
        if (url.matches()) {
            return url.group(1);
        }
        if (!trimmed.contains("/") && trimmed.matches(".*\\.(?:jpg|png|gif|webp|bmp)")) {
            return trimmed;
        }
        return null;
    }
}
