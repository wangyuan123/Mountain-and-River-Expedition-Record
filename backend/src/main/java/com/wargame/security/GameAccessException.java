package com.wargame.security;

/** 游戏许可拒绝与登录失效分开，客户端收到后进入实名或休息页。 */
public class GameAccessException extends RuntimeException {
    private final String code;
    private final int status;
    public GameAccessException(String code, String message) { this(code, message, 403); }
    public GameAccessException(String code, String message, int status) {
        super(message); this.code = code; this.status = status;
    }
    public String getCode() { return code; }
    public int getStatus() { return status; }
}
