package com.labroute.web;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException badRequest(String code, String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, msg);
    }

    public static ApiException notFound(String code, String msg) {
        return new ApiException(HttpStatus.NOT_FOUND, code, msg);
    }

    public static ApiException conflict(String code, String msg, Map<String, Object> details) {
        return new ApiException(HttpStatus.CONFLICT, code, msg, details);
    }

    public static ApiException unauthorized(String code, String msg) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, msg);
    }

    public static ApiException forbidden(String code, String msg) {
        return new ApiException(HttpStatus.FORBIDDEN, code, msg);
    }
}
