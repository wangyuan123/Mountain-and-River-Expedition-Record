package com.wargame.service;

import com.wargame.model.entity.Player;
import com.wargame.security.AccountException;
import com.wargame.service.compliance.AntiAddictionService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/** 调用方在账号行锁内变更凭据，保持密码更新和所有旧会话失效一致。 */
@Service
public class AccountCredentialService {
    private final PasswordEncoder passwords;
    private final AntiAddictionService protection;
    private final WebSocketPushService push;
    public AccountCredentialService(PasswordEncoder passwords, AntiAddictionService protection, WebSocketPushService push) {
        this.passwords = passwords; this.protection = protection; this.push = push;
    }
    public static void validatePassword(String password) {
        if (password == null || password.isBlank() || password.length() < 6 || password.length() > 64
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new AccountException("PASSWORD_INVALID", "新密码需为 6—64 个字符，且不超过 72 个 UTF-8 字节");
    }
    @Transactional
    public void changePassword(Player player, String password) {
        validatePassword(password);
        player.setPasswordHash(passwords.encode(password));
        invalidateSessions(player);
    }
    @Transactional
    public void invalidateSessions(Player player) {
        protection.endAccountSessions(player);
        player.setAuthVersion(player.getAuthVersion() + 1);
        player.setRecoveryTokenHash(null);
        player.setRecoveryTokenExpiresAt(0L);
        push.disconnectAccountAfterCommit(player.getId());
    }
}
