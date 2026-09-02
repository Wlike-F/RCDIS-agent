package com.rcdis.agent.service.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.config.StorageProperties;
import com.rcdis.agent.service.FileStorageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageServiceImpl implements FileStorageService {

    private static final String RECEIPT_SUBDIR = "receipts";
    private static final String URL_PREFIX = "/uploads";
    private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;

    // Allowed image content types mapped to their file extensions.
    private static final Map<String, String> ALLOWED_IMAGE_TYPES = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/gif", ".gif",
            "image/webp", ".webp",
            "image/bmp", ".bmp");

    private final StorageProperties storageProperties;

    @Override
    public String storeReceiptImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("FILE_REQUIRED", "Upload file must not be empty", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new BusinessException(
                    "FILE_TOO_LARGE",
                    "Receipt image must not exceed 10MB. size=" + file.getSize(),
                    HttpStatus.BAD_REQUEST);
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String extension = ALLOWED_IMAGE_TYPES.get(contentType);
        if (extension == null) {
            throw new BusinessException(
                    "FILE_TYPE_NOT_ALLOWED",
                    "Only jpg/png/gif/webp/bmp images are allowed. contentType=" + file.getContentType(),
                    HttpStatus.BAD_REQUEST);
        }

        // Generated UUID file name only: user-supplied names never touch the file system.
        String fileName = UUID.randomUUID() + extension;
        Path targetDir = uploadDir().resolve(RECEIPT_SUBDIR);
        try {
            Files.createDirectories(targetDir);
            file.transferTo(targetDir.resolve(fileName));
        } catch (IOException exception) {
            throw new BusinessException(
                    "FILE_STORAGE_FAILED",
                    "Failed to store uploaded receipt image: " + exception.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        log.atInfo()
                .addKeyValue("fileName", fileName)
                .addKeyValue("size", file.getSize())
                .log("Receipt image stored");
        return URL_PREFIX + "/" + RECEIPT_SUBDIR + "/" + fileName;
    }

    @Override
    public Path uploadDir() {
        return Path.of(storageProperties.getUploadDir()).toAbsolutePath().normalize();
    }
}
