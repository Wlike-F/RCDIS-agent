package com.rcdis.agent.agent.tools;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.agent.AgentToolContext;
import com.rcdis.agent.config.AgentProperties;
import com.rcdis.agent.entity.AgentAttachmentEntity;
import com.rcdis.agent.entity.ReceiptOcrEntity;
import com.rcdis.agent.service.AgentAttachmentService;
import com.rcdis.agent.service.FileStorageService;
import com.rcdis.agent.service.ReceiptOcrService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns chat attachments into official receipt references so a user-uploaded image can be
 * attached to a reimbursement line through the canonical receiptFile reference.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReceiptTools {

    private static final String TOOL_REGISTER = "register_attachment_receipt";
    private static final String TOOL_GET_OCR = "get_receipt_ocr";

    private final AgentAttachmentService agentAttachmentService;
    private final FileStorageService fileStorageService;
    private final ReceiptOcrService receiptOcrService;
    private final com.rcdis.agent.config.AgentProperties agentProperties;
    private final ObjectMapper objectMapper;

    @Tool(name = TOOL_REGISTER,
            description = "把当前对话中用户上传的附件登记为正式凭证（直接执行，不改财务数据），登记后自动触发识别。"
                    + "支持图片（截图/拍照凭证）与 PDF 电子发票；参数 attachmentName 为附件显示名（对话附件列表中的文件名）。"
                    + "返回 receiptFile 正式引用，应原样填入 create_reimbursement 明细行的 receiptFile 字段。"
                    + "文本/Word 等其他附件不能作为凭证，聊天附件本身不能直接当 receiptFile 填。")
    public String registerAttachmentReceipt(
            @ToolParam(description = "附件显示名，例如 Code_Generated_Image.png 或 发票.pdf") String attachmentName,
            ToolContext toolContext) {
        AgentToolContext ctx = AgentToolContext.from(toolContext);
        ToolReporting.start(ctx, TOOL_REGISTER, Map.of("attachmentName", String.valueOf(attachmentName)));
        try {
            if (ctx == null || ctx.currentUser() == null) {
                ToolReporting.failure(ctx, TOOL_REGISTER, "缺少用户身份");
                return json(Map.of("ok", false, "error", "缺少用户身份，无法登记凭证"));
            }
            if (ctx.conversationId() == null || ctx.conversationId().isBlank()) {
                ToolReporting.failure(ctx, TOOL_REGISTER, "缺少对话上下文");
                return json(Map.of("ok", false, "error", "缺少对话上下文，无法定位附件"));
            }
            if (attachmentName == null || attachmentName.isBlank()) {
                ToolReporting.failure(ctx, TOOL_REGISTER, "缺少附件名");
                return json(Map.of("ok", false, "error", "请提供附件显示名 attachmentName"));
            }
            AgentAttachmentEntity attachment = agentAttachmentService.findLatestRegisterableByName(
                    attachmentName.trim(), ctx.conversationId(), ctx.currentUser().userId());
            String receiptFile = fileStorageService.registerReceiptFromExistingFile(
                    Path.of(attachment.getStoredPath()), attachment.getMime(), attachment.getSizeBytes(),
                    ctx.currentUser().userId());
            // Fire-and-forget: images go through the vision model, PDFs through text extraction.
            receiptOcrService.recognizeAsync(receiptFile);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("attachmentId", attachment.getId());
            result.put("originalName", attachment.getOriginalName());
            result.put("receiptFile", receiptFile);
            result.put("ocrTriggered", true);
            ToolReporting.success(ctx, TOOL_REGISTER);
            return json(result);
        } catch (RuntimeException exception) {
            ToolReporting.failure(ctx, TOOL_REGISTER, exception.getMessage());
            return json(Map.of("ok", false, "error", "登记凭证失败：" + exception.getMessage()));
        }
    }

    @Tool(name = TOOL_GET_OCR,
            description = "读取某张已上传凭证的 OCR 识别结果（只读）。若识别尚未结束，本工具会阻塞等待其完成（最长约 45 秒）再返回，"
                    + "因此 register_attachment_receipt 之后应立即调用本工具读取字段并向用户汇报，不要让用户稍后再问。"
                    + "参数 receiptFileOrName 可传凭证正式引用或文件名。"
                    + "返回 recognized 是否完成、status（DONE/FAILED/PENDING）、doc_type（INVOICE 发票 / WECHAT_PAY 微信截图 / ALIPAY_PAY 支付截图 / UNKNOWN）、"
                    + "结构化字段（invoice_no 发票号、total_amount 价税合计、seller_name 销售方、pay_no 支付单号、amount 金额、counterparty 收款方）"
                    + "与 confidence 置信度。识别结果仅供参考，关键金额仍需与用户确认。")
    public String getReceiptOcr(
            @ToolParam(description = "凭证正式引用或文件名") String receiptFileOrName,
            ToolContext toolContext) {
        AgentToolContext ctx = AgentToolContext.from(toolContext);
        ToolReporting.start(ctx, TOOL_GET_OCR, Map.of("receiptFileOrName", String.valueOf(receiptFileOrName)));
        try {
            com.rcdis.agent.vo.ReceiptOcrVO ocr = receiptOcrService.getByFileName(receiptFileOrName);
            boolean unfinished = ocr == null || (!ReceiptOcrEntity.STATUS_DONE.equals(ocr.status())
                    && !ReceiptOcrEntity.STATUS_FAILED.equals(ocr.status()));
            if (unfinished) {
                int timeout = Math.max(1, agentProperties.getOcr().getWaitTimeoutSeconds());
                ocr = receiptOcrService.waitForResult(receiptFileOrName, Duration.ofSeconds(timeout));
            }
            if (ocr == null) {
                ToolReporting.success(ctx, TOOL_GET_OCR);
                return json(Map.of("ok", true, "recognized", false, "status", "PENDING",
                        "message", "识别仍在进行且已超过等待上限，请稍后再次调用本工具，或向用户人工确认字段"));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("recognized", ReceiptOcrEntity.STATUS_DONE.equals(ocr.status()));
            result.put("status", ocr.status());
            if (ReceiptOcrEntity.STATUS_FAILED.equals(ocr.status())) {
                result.put("errorMessage", ocr.errorMessage());
            }
            result.put("docType", ocr.docType());
            result.put("fieldsJson", ocr.fieldsJson());
            result.put("confidence", ocr.confidence());
            ToolReporting.success(ctx, TOOL_GET_OCR);
            return json(result);
        } catch (RuntimeException exception) {
            ToolReporting.failure(ctx, TOOL_GET_OCR, exception.getMessage());
            return json(Map.of("ok", false, "error", "读取凭证识别结果失败：" + exception.getMessage()));
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{\"ok\":false,\"error\":\"序列化工具结果失败\"}";
        }
    }
}
