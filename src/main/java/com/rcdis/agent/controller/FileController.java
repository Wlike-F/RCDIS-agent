package com.rcdis.agent.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.rcdis.agent.common.response.ApiResponse;
import com.rcdis.agent.dto.FileUploadResponse;
import com.rcdis.agent.service.FileStorageService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Files")
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    @Operation(summary = "Upload receipt image (invoice / payment proof)")
    @PostMapping(value = "/receipt-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileUploadResponse> uploadReceiptImage(@RequestParam("file") MultipartFile file) {
        String url = fileStorageService.storeReceiptImage(file);
        return ApiResponse.success(new FileUploadResponse(url));
    }
}
