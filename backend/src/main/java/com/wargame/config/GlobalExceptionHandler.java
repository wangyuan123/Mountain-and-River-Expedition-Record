package com.wargame.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.OptimisticLockingFailureException;
import jakarta.persistence.OptimisticLockException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(com.wargame.security.GameAccessException.class)
    public ResponseEntity<Map<String, Object>> handleGameAccess(com.wargame.security.GameAccessException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage(), "code", e.getCode()));
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<Map<String, String>> handleConcurrentUpdate(Exception e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "游戏状态刚刚发生变化，本次操作未生效，请刷新后重试"));
    }

    @ExceptionHandler(com.wargame.security.AccountException.class)
    public ResponseEntity<Map<String, Object>> handleAccount(com.wargame.security.AccountException e) {
        HttpStatus status = "RATE_LIMITED".equals(e.getCode()) ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage(), "code", e.getCode(), "retryAfter", e.getRetryAfter()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntime(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
    }
}
