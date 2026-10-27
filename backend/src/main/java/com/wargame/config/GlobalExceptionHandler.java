package com.wargame.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.persistence.OptimisticLockException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
                .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "请求参数无效"));
    }

    /** 参数转换失败属于客户端输入错误，不能被兜底处理成战区维护。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleArgumentTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "请求参数 " + e.getName() + " 格式无效"));
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Map<String, Object>> handleException(Throwable e) {
        log.error("Unhandled exception processing request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "error", "战区服务器维护中，请稍后重试",
                    "code", "SERVER_MAINTENANCE",
                    "status", 500
                ));
    }
}
