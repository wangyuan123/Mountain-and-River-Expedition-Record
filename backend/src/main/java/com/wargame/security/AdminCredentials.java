package com.wargame.security;

import java.nio.charset.StandardCharsets;

/** 后台凭据规则独立于玩家密码，防止继续使用历史公开的初始化口令。 */
public final class AdminCredentials {
    public static final String LEGACY_DEFAULT_HASH = "$2a$10$mC/yoWP5YqabRpdNOVgUCewGbyw8qiodZplD3i/hJEYMe4S4VTX1i";
    private AdminCredentials() {}

    public static void validateNewPassword(String password) {
        if (password == null || password.isBlank() || password.length() < 12 || password.length() > 64
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("管理员新密码需为 12—64 个字符，且不超过 72 个 UTF-8 字节");
    }

    public static boolean allowedRole(String role) {
        return "ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
    }
}
