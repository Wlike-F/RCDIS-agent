package com.rcdis.agent.service.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.config.StorageProperties;
import com.rcdis.agent.entity.UploadedFileEntity;
import com.rcdis.agent.entity.ReimbursementItemEntity;
import com.rcdis.agent.mapper.UploadedFileMapper;
import com.rcdis.agent.mapper.ReimbursementItemMapper;
import com.rcdis.agent.service.FileStorageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageServiceImpl implements FileStorageService {

    private static final String RECEIPT_SUBDIR = "receipts";
    private static final String URL_PREFIX = "/api/files/receipts";
    private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;
    private static final Pattern RECEIPT_URL_PATTERN = Pattern.compile(
            "^/api/files/receipts/([0-9a-fA-F-]{36}\\.(?:jpg|png|gif|webp|bmp|pdf))/content$");
    private static final Set<String> RECEIPT_EXTENSIONS =
            Set.of(".jpg", ".png", ".gif", ".webp", ".bmp", ".pdf");

    // Allowed image content types mapped to their file extensions.
    private static final Map<String, String> ALLOWED_IMAGE_TYPES = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/gif", ".gif",
            "image/webp", ".webp",
            "image/bmp", ".bmp");

    private final StorageProperties storageProperties;
    private final UploadedFileMapper uploadedFileMapper;
    private final ReimbursementItemMapper reimbursementItemMapper;

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

        UploadedFileEntity metadata = new UploadedFileEntity();
        metadata.setOwnerUserId(CurrentUserContextHolder.currentOrAnonymous().userId());
        metadata.setCategory("RECEIPT");
        metadata.setFileName(fileName);
        metadata.setStoredPath(targetDir.resolve(fileName).toAbsolutePath().normalize().toString());
        metadata.setMime(contentType);
        metadata.setSizeBytes(file.getSize());
        metadata.setCreatedBy(metadata.getOwnerUserId());
        uploadedFileMapper.insert(metadata);

        log.atInfo()
                .addKeyValue("fileName", fileName)
                .addKeyValue("size", file.getSize())
                .log("Receipt image stored");
        return URL_PREFIX + "/" + fileName + "/content";
    }

    @Override
    public Path uploadDir() {
        return Path.of(storageProperties.getUploadDir()).toAbsolutePath().normalize();
    }

    @Override
    public UploadedFileEntity loadAuthorizedReceipt(String fileName) {
        UploadedFileEntity file = findReceipt(fileName);
        if (file == null) {
            throw new BusinessException("FILE_NOT_FOUND", "Receipt file was not found", HttpStatus.NOT_FOUND);
        }
        CurrentUserTO current = CurrentUserContextHolder.currentOrAnonymous();
        boolean linked = reimbursementItemMapper.selectCount(new LambdaQueryWrapper<ReimbursementItemEntity>()
                .eq(ReimbursementItemEntity::getReceiptFile, URL_PREFIX + "/" + fileName + "/content")) > 0;
        if (!current.userId().equals(file.getOwnerUserId())
                && !current.hasRole("ADMIN")
                && !(current.hasRole("APPROVER") && linked)) {
            throw new BusinessException("FILE_FORBIDDEN", "You cannot access this receipt file", HttpStatus.FORBIDDEN);
        }
        return file;
    }

    @Override
    public void validateOwnedReceiptReference(String receiptUrl) {
        Matcher matcher = RECEIPT_URL_PATTERN.matcher(receiptUrl == null ? "" : receiptUrl.trim());
        if (!matcher.matches()) {
            throw new BusinessException("RECEIPT_REFERENCE_INVALID", "Receipt must reference an uploaded receipt file", HttpStatus.BAD_REQUEST);
        }
        UploadedFileEntity file = findReceipt(matcher.group(1));
        CurrentUserTO current = CurrentUserContextHolder.currentOrAnonymous();
        if (file == null || (!current.hasRole("ADMIN") && !current.userId().equals(file.getOwnerUserId()))) {
            throw new BusinessException("RECEIPT_REFERENCE_FORBIDDEN", "Receipt file is missing or belongs to another user", HttpStatus.FORBIDDEN);
        }
    }

    @Override
    public String registerReceiptFromExistingFile(Path source, String mime, Long sizeBytes, String ownerUserId) {
        Path from = source == null ? null : source.toAbsolutePath().normalize();
        if (from == null || !Files.isRegularFile(from)) {
            throw new BusinessException(
                    "FILE_NOT_FOUND", "Source file to register as receipt was not found", HttpStatus.NOT_FOUND);
        }
        String extension = extensionOf(from.getFileName().toString());
        if (!RECEIPT_EXTENSIONS.contains(extension)) {
            throw new BusinessException(
                    "FILE_TYPE_NOT_ALLOWED",
                    "Only jpg/png/gif/webp/bmp images and pdf e-invoices can be registered as receipts. ext=" + extension,
                    HttpStatus.BAD_REQUEST);
        }

        // Receipt content is served from the receipts directory only, so the bytes must be copied.
        String fileName = UUID.randomUUID() + extension;
        Path targetDir = uploadDir().resolve(RECEIPT_SUBDIR);
        Path target = targetDir.resolve(fileName);
        try {
            Files.createDirectories(targetDir);
            Files.copy(from, target);
        } catch (IOException exception) {
            throw new BusinessException(
                    "FILE_STORAGE_FAILED",
                    "Failed to register receipt file: " + exception.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        UploadedFileEntity metadata = new UploadedFileEntity();
        metadata.setOwnerUserId(ownerUserId);
        metadata.setCategory("RECEIPT");
        metadata.setFileName(fileName);
        metadata.setStoredPath(target.toAbsolutePath().normalize().toString());
        metadata.setMime(mime);
        metadata.setSizeBytes(sizeBytes == null ? 0L : sizeBytes);
        // Explicit because tool threads have no request identity for the audit meta handler.
        metadata.setCreatedBy(ownerUserId);
        uploadedFileMapper.insert(metadata);

        log.atInfo()
                .addKeyValue("fileName", fileName)
                .addKeyValue("ownerUserId", ownerUserId)
                .log("Existing file registered as receipt");
        return URL_PREFIX + "/" + fileName + "/content";
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private UploadedFileEntity findReceipt(String fileName) {
        return uploadedFileMapper.selectOne(new LambdaQueryWrapper<UploadedFileEntity>()
                .eq(UploadedFileEntity::getCategory, "RECEIPT")
                .eq(UploadedFileEntity::getFileName, fileName)
                .last("LIMIT 1"));
    }

    @Override
    public Path contentPath(UploadedFileEntity file) {
        Path root = uploadDir().resolve(RECEIPT_SUBDIR).normalize();
        Path path = Path.of(file.getStoredPath()).toAbsolutePath().normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            throw new BusinessException("FILE_NOT_FOUND", "Receipt file content was not found", HttpStatus.NOT_FOUND);
        }
        return path;
    }
}
