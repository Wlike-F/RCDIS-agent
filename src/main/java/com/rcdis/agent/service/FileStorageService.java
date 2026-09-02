package com.rcdis.agent.service;

import java.nio.file.Path;

import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {

    /**
     * Store an uploaded receipt image (invoice / payment proof) and return
     * the relative URL used to access it, e.g. /uploads/receipts/xxx.png.
     */
    String storeReceiptImage(MultipartFile file);

    /**
     * Absolute directory where uploaded files are stored.
     */
    Path uploadDir();
}
