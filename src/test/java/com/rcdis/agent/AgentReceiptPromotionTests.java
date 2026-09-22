package com.rcdis.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.config.StorageProperties;
import com.rcdis.agent.entity.AgentAttachmentEntity;
import com.rcdis.agent.entity.UploadedFileEntity;
import com.rcdis.agent.mapper.AgentAttachmentMapper;
import com.rcdis.agent.service.AgentAttachmentService;
import com.rcdis.agent.service.FileStorageService;

/**
 * End-to-end promotion of a chat attachment into a canonical receipt reference: ownership is
 * enforced with explicit parameters (tool threads carry no request identity), the bytes are
 * copied into the receipts directory, and the returned reference passes validation.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "rcdis.feishu.enabled=false",
        "rcdis.feishu.client-type=noop"
})
class AgentReceiptPromotionTests {

    @Autowired
    private AgentAttachmentService agentAttachmentService;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private StorageProperties storageProperties;

    @Autowired
    private AgentAttachmentMapper agentAttachmentMapper;

    @AfterEach
    void clearUser() {
        CurrentUserContextHolder.clear();
    }

    @Test
    void promotesOwnedConversationImageIntoCanonicalReceiptReference() throws IOException {
        String conversation = "conv-promote-" + UUID.randomUUID();
        seedAttachment(conversation, "receipt.png", "u-promoter", ".png");

        AgentAttachmentEntity attachment =
                agentAttachmentService.findLatestRegisterableByName("receipt.png", conversation, "u-promoter");
        String receiptFile = fileStorageService.registerReceiptFromExistingFile(
                Path.of(attachment.getStoredPath()), attachment.getMime(), attachment.getSizeBytes(), "u-promoter");

        assertThat(receiptFile).matches("^/api/files/receipts/[0-9a-fA-F-]{36}\\.png/content$");
        String fileName = receiptFile.split("/")[4];
        CurrentUserContextHolder.set(user("u-promoter", "promoter"));
        UploadedFileEntity stored = fileStorageService.loadAuthorizedReceipt(fileName);
        assertThat(stored.getCategory()).isEqualTo("RECEIPT");
        assertThat(stored.getOwnerUserId()).isEqualTo("u-promoter");
        assertThat(stored.getStoredPath()).contains("receipts").doesNotContain("agent-attachments");
        // The promoted reference must pass the same validation the execute path applies.
        fileStorageService.validateOwnedReceiptReference(receiptFile);
    }

    @Test
    void attachmentOfAnotherUserIsInvisibleToThePromotionLookup() throws IOException {
        String conversation = "conv-foreign-" + UUID.randomUUID();
        seedAttachment(conversation, "foreign.png", "u-owner", ".png");

        assertThatThrownBy(() ->
                agentAttachmentService.findLatestRegisterableByName("foreign.png", conversation, "u-attacker"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void pdfInvoiceAttachmentRegistersIntoCanonicalReceiptReference() throws IOException {
        String conversation = "conv-pdf-" + UUID.randomUUID();
        Path dir = Path.of(storageProperties.getUploadDir()).resolve("agent-attachments");
        Files.createDirectories(dir);
        Path pdf = dir.resolve(UUID.randomUUID() + ".pdf");
        Files.write(pdf, new byte[] {1, 2, 3, 4});

        AgentAttachmentEntity entity = new AgentAttachmentEntity();
        entity.setConversationId(conversation);
        entity.setOriginalName("电子发票.pdf");
        entity.setStoredPath(pdf.toString());
        entity.setUrl("PENDING");
        entity.setMime("application/pdf");
        entity.setExt("pdf");
        entity.setSizeBytes(4L);
        entity.setKind(AgentAttachmentEntity.KIND_PDF);
        entity.setExtractStatus(AgentAttachmentEntity.EXTRACT_OK);
        entity.setCreatedBy("u-pdf");
        entity.setCreatedAt(OffsetDateTime.now());
        agentAttachmentMapper.insert(entity);

        AgentAttachmentEntity attachment =
                agentAttachmentService.findLatestRegisterableByName("电子发票.pdf", conversation, "u-pdf");
        String receiptFile = fileStorageService.registerReceiptFromExistingFile(
                Path.of(attachment.getStoredPath()), attachment.getMime(), attachment.getSizeBytes(), "u-pdf");

        assertThat(receiptFile).matches("^/api/files/receipts/[0-9a-fA-F-]{36}\\.pdf/content$");
        CurrentUserContextHolder.set(user("u-pdf", "pdf-owner"));
        fileStorageService.validateOwnedReceiptReference(receiptFile);
    }

    @Test
    void nonImageFilesCannotBeRegisteredAsReceipts() throws IOException {
        Path dir = Path.of(storageProperties.getUploadDir()).resolve("agent-attachments");
        Files.createDirectories(dir);
        Path textFile = dir.resolve(UUID.randomUUID() + ".txt");
        Files.write(textFile, "not an image".getBytes());

        assertThatThrownBy(() ->
                fileStorageService.registerReceiptFromExistingFile(textFile, "text/plain", 12L, "u-promoter"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("FILE_TYPE_NOT_ALLOWED"));
    }

    private void seedAttachment(String conversationId, String originalName, String ownerUserId, String ext)
            throws IOException {
        Path dir = Path.of(storageProperties.getUploadDir()).resolve("agent-attachments");
        Files.createDirectories(dir);
        Path file = dir.resolve(UUID.randomUUID() + ext);
        Files.write(file, new byte[] {1, 2, 3, 4});

        AgentAttachmentEntity entity = new AgentAttachmentEntity();
        entity.setConversationId(conversationId);
        entity.setOriginalName(originalName);
        entity.setStoredPath(file.toString());
        entity.setUrl("PENDING");
        entity.setMime("image/png");
        entity.setExt(ext.substring(1));
        entity.setSizeBytes(4L);
        entity.setKind(AgentAttachmentEntity.KIND_IMAGE);
        entity.setExtractStatus(AgentAttachmentEntity.EXTRACT_UNSUPPORTED);
        entity.setCreatedBy(ownerUserId);
        entity.setCreatedAt(OffsetDateTime.now());
        agentAttachmentMapper.insert(entity);
    }

    private CurrentUserTO user(String id, String username) {
        return CurrentUserTO.of(id, username, "test", null, Set.of("RESEARCHER"));
    }
}
