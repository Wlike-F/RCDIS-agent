package com.rcdis.agent.common.exception;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.rcdis.agent.common.response.ApiResponse;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        log.atWarn()
                .addKeyValue("code", exception.getCode())
                .log("Business exception");
        return ResponseEntity.status(exception.getStatus())
                .body(ApiResponse.failure(exception.getCode(), exception.getMessage()));
    }

    /**
     * A malformed request body is the caller's mistake, not a server fault. Without this mapping the
     * catch-all below reported it as a 500 "unexpected server error", hiding the real cause from the
     * client and inventing server instability. The response never echoes the raw body: request bodies
     * here can carry amounts, account numbers or attachments.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        log.atWarn()
                .addKeyValue("code", "REQUEST_BODY_INVALID")
                .addKeyValue("cause", exception.getMostSpecificCause().getClass().getSimpleName())
                .log("Malformed request body");
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("REQUEST_BODY_INVALID", "请求体解析失败，请检查 JSON 格式是否合法"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.atWarn()
                .addKeyValue("errorCount", exception.getBindingResult().getErrorCount())
                .log("Request validation failed");
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("REQUEST_VALIDATION_FAILED", message));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBindException(BindException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.atWarn()
                .addKeyValue("errorCount", exception.getBindingResult().getErrorCount())
                .log("Request binding failed");
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("REQUEST_BINDING_FAILED", message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(
            ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .collect(Collectors.joining("; "));
        log.atWarn()
                .addKeyValue("errorCount", exception.getConstraintViolations().size())
                .log("Request constraint validation failed");
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("REQUEST_CONSTRAINT_VALIDATION_FAILED", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.atError()
                .setCause(exception)
                .log("Unexpected application exception");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure("INTERNAL_SERVER_ERROR", "Unexpected server error"));
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }
}
