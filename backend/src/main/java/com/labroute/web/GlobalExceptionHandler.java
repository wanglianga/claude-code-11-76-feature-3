package com.labroute.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", ex.status().value());
        body.put("code", ex.code());
        body.put("message", ex.getMessage());
        if (!ex.details().isEmpty()) {
            body.put("details", ex.details());
        }
        return ResponseEntity.status(ex.status()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        return handleApi(new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "request validation failed", fields));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        log.error("unhandled error", ex);
        return handleApi(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                ex.getClass().getSimpleName() + ": " + ex.getMessage()));
    }
}
