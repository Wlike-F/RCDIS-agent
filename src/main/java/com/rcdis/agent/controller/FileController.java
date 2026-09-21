package com.rcdis.agent.controller;

import java.nio.file.Path;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.dto.FileUploadResponse;
import com.rcdis.agent.service.AgentAttachmentService;
import com.rcdis.agent.service.FileStorageService;
import com.rcdis.agent.service.ReceiptOcrService;
import com.rcdis.agent.vo.AgentAttachmentVO;
import com.rcdis.agent.vo.ReceiptOcrVO;
import com.rcdis.agent.entity.AgentAttachmentEntity;
import com.rcdis.agent.entity.UploadedFileEntity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Files")
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;
    private final AgentAttachmentService agentAttachmentService;
    private final ReceiptOcrService receiptOcrService;

    @Operation(summary = "Upload receipt image (invoice / payment proof)")
    @PostMapping(value = "/receipt-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileUploadResponse> uploadReceiptImage(@RequestParam("file") MultipartFile file) {
        String url = fileStorageService.storeReceiptImage(file);
        // Fire-and-forget: recognition latency or failure must never affect the upload response.
        receiptOcrService.recognizeAsync(url);
        return ApiResponse.success(new FileUploadResponse(url));
    }

    @Operation(summary = "Get the OCR recognition result of an owned receipt")
    @GetMapping("/receipt-ocr")
    public ApiResponse<ReceiptOcrVO> receiptOcr(@RequestParam("receiptFile") String receiptFile) {
        String fileName = receiptFile.startsWith("/api/files/receipts/")
                ? receiptFile.substring("/api/files/receipts/".length()).replaceFirst("/content$", "")
                : receiptFile;
        // Ownership check first: only the owner (or an admin) may read a receipt's OCR result.
        fileStorageService.loadAuthorizedReceipt(fileName);
        return ApiResponse.success(receiptOcrService.getByFileName(fileName));
    }

    @Operation(summary = "Re-run OCR recognition for an owned receipt")
    @PostMapping("/receipt-ocr/rerun")
    public ApiResponse<Boolean> rerunReceiptOcr(@RequestParam("receiptFile") String receiptFile) {
        String fileName = receiptFile.startsWith("/api/files/receipts/")
                ? receiptFile.substring("/api/files/receipts/".length()).replaceFirst("/content$", "")
                : receiptFile;
        fileStorageService.loadAuthorizedReceipt(fileName);
        receiptOcrService.recognizeAsync(receiptFile);
        return ApiResponse.success(Boolean.TRUE);
    }

    @Operation(summary = "Upload an Agent conversation attachment (pdf/docx/pptx/txt/image)")
    @PostMapping(value = "/agent-attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AgentAttachmentVO> uploadAgentAttachment(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "conversationId", required = false) String conversationId) {
        return ApiResponse.success(agentAttachmentService.store(file, conversationId));
    }

    @Operation(summary = "Download an owned Agent attachment")
    @GetMapping("/agent-attachments/{id}/content")
    public ResponseEntity<FileSystemResource> downloadAgentAttachment(@PathVariable Long id) {
        AgentAttachmentEntity attachment = agentAttachmentService.findOwnedById(id);
        Path path = agentAttachmentService.contentPath(attachment);
        MediaType contentType = parseMediaType(attachment.getMime());
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(attachment.getOriginalName(), java.nio.charset.StandardCharsets.UTF_8)
                        .build().toString())
                .body(new FileSystemResource(path));
    }

    @Operation(summary = "Download an authorized reimbursement receipt")
    @GetMapping("/receipts/{fileName}/content")
    public ResponseEntity<FileSystemResource> downloadReceipt(@PathVariable String fileName) {
        UploadedFileEntity file = fileStorageService.loadAuthorizedReceipt(fileName);
        return ResponseEntity.ok()
                .contentType(parseMediaType(file.getMime()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(fileName).build().toString())
                .body(new FileSystemResource(fileStorageService.contentPath(file)));
    }

    private MediaType parseMediaType(String mime) {
        try {
            return mime == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(mime);
        } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
