package com.wargame.security;

/** 账号流程的稳定错误码，供前端区分验证失败与需要用户处理的状态。 */
public class AccountException extends IllegalArgumentException {
    private final String code;
    private final long retryAfter;

    public AccountException(String code, String message) { this(code, message, 0); }
    public AccountException(String code, String message, long retryAfter) {
        super(message);
        this.code = code;
        this.retryAfter = retryAfter;
    }
    public String getCode() { return code; }
    public long getRetryAfter() { return retryAfter; }
}
