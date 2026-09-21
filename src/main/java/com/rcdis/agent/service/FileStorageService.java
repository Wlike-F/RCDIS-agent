package com.rcdis.agent.service;

import java.nio.file.Path;

import org.springframework.web.multipart.MultipartFile;
import com.rcdis.agent.entity.UploadedFileEntity;

public interface FileStorageService {

    /**
     * Store an uploaded receipt image (invoice / payment proof) and return
     * the authenticated API URL used to access it.
     */
    String storeReceiptImage(MultipartFile file);

    /**
     * Absolute directory where uploaded files are stored.
     */
    Path uploadDir();

    UploadedFileEntity loadAuthorizedReceipt(String fileName);

    Path contentPath(UploadedFileEntity file);

    /** Validates a receipt reference is canonical and owned by the current user (or an admin). */
    void validateOwnedReceiptReference(String receiptUrl);

    /**
     * Registers an already-stored file (e.g. a chat attachment) as a receipt: copies the bytes
     * into the receipts directory and inserts RECEIPT metadata owned by the given user. Explicit
     * owner parameter keeps this callable from tool threads without request identity.
     *
     * @return the canonical receiptFile reference for reimbursement item lines.
     */
    String registerReceiptFromExistingFile(Path source, String mime, Long sizeBytes, String ownerUserId);
}
