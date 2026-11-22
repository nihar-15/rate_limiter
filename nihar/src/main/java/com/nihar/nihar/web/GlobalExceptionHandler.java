package com.nihar.nihar.web;

import com.nihar.nihar.exception.AdminApiDisabledException;
import com.nihar.nihar.exception.InvalidRuleException;
import com.nihar.nihar.exception.RuleConflictException;
import com.nihar.nihar.exception.RuleNotFoundException;
import com.nihar.nihar.web.dto.ApiError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/** Translates domain and validation failures into the uniform {@link ApiError}. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {

        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();

        return ResponseEntity.badRequest().body(ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "Request validation failed",
                details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {

        return ResponseEntity.badRequest().body(ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                "MALFORMED_REQUEST",
                "Request body could not be parsed. Check the JSON syntax and that "
                        + "'algorithm' is one of FIXED_WINDOW, SLIDING_WINDOW, TOKEN_BUCKET."));
    }

    @ExceptionHandler(InvalidRuleException.class)
    public ResponseEntity<ApiError> handleInvalidRule(InvalidRuleException ex) {

        return ResponseEntity.badRequest().body(ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_RULE",
                ex.getMessage()));
    }

    @ExceptionHandler(RuleNotFoundException.class)
    public ResponseEntity<ApiError> handleRuleNotFound(RuleNotFoundException ex) {

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(
                HttpStatus.NOT_FOUND.value(),
                "RULE_NOT_FOUND",
                ex.getMessage()));
    }

    @ExceptionHandler(RuleConflictException.class)
    public ResponseEntity<ApiError> handleConflict(RuleConflictException ex) {

        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(
                HttpStatus.CONFLICT.value(),
                "RULE_CONFLICT",
                ex.getMessage()));
    }

    @ExceptionHandler(AdminApiDisabledException.class)
    public ResponseEntity<ApiError> handleDisabled(AdminApiDisabledException ex) {

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(
                HttpStatus.FORBIDDEN.value(),
                "ADMIN_API_DISABLED",
                ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {

        return ResponseEntity.badRequest().body(ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_REQUEST",
                ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex) {

        log.warn("Rate limiting could not complete: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(
                HttpStatus.CONFLICT.value(),
                "RATE_LIMIT_STATE_ERROR",
                ex.getMessage()));
    }
}