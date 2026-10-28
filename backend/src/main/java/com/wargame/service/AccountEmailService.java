package com.wargame.service;

import com.wargame.config.AccountEmailProperties;
import com.wargame.config.GameServerIdentity;
import com.wargame.model.UserPrincipal;
import com.wargame.model.entity.AccountEmailRecords.*;
import com.wargame.model.entity.Player;
import com.wargame.repository.PlayerRepository;
import com.wargame.security.AccountException;
import com.wargame.security.GameAccessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Supplier;

/** 验证流程和账号变更使用短事务；SMTP 永不在这些事务中执行。 */
@Service
public class AccountEmailService {
    private final EntityManager em;
    private final PlayerRepository players;
    private final PasswordEncoder passwords;
    private final AccountEmailVault vault;
    private final AccountEmailProperties config;
    private final GameServerIdentity server;
    private final AccountCredentialService credentials;
    private final com.wargame.service.admin.AdminAuditLogService audit;
    private final TransactionTemplate transactions;
    private static final long MINUTE = 60_000L, HOUR = 3_600_000L, DAY = 86_400_000L;

    public AccountEmailService(EntityManager em, PlayerRepository players, PasswordEncoder passwords,
                               AccountEmailVault vault, AccountEmailProperties config, GameServerIdentity server,
                               AccountCredentialService credentials, com.wargame.service.admin.AdminAuditLogService audit,
                               PlatformTransactionManager transactions) {
        this.em = em; this.players = players; this.passwords = passwords; this.vault = vault;
        this.config = config; this.server = server; this.credentials = credentials;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactions);
    }

    public Map<String, Object> capabilities() {
        return Map.of("registrationEmailRequired", true, "emailBindingEnabled", true,
                "passwordResetEnabled", true, "mailAvailable", config.available(), "supportUrl", config.getSupportUrl());
    }

    private void available() {
        if (!config.available()) throw new GameAccessException("MAIL_UNAVAILABLE", "邮箱服务暂不可用，请稍后重试；已有账号可继续使用密码登录", 503);
    }

    private record Result<T>(T value, AccountException error) {}

    /** 业务校验失败仍提交尝试次数；数据写入异常则正常回滚整个事务。 */
    private <T> T execute(Supplier<T> operation) {
        available();
        Result<T> result = transactions.execute(status -> {
            // 第一版用持久化单行闸锁统一槽位、限额和账号的锁顺序，锁内没有网络投递。
            em.createNativeQuery("insert ignore into account_email_gate (id) values (1)").executeUpdate();
            em.find(Gate.class, 1, LockModeType.PESSIMISTIC_WRITE);
            try { return new Result<>(operation.get(), null); }
            catch (AccountException e) { return new Result<>(null, e); }
        });
        if (result.error() != null) throw result.error();
        return result.value();
    }

    private String emailKey(String email) { return vault.digest("email|" + server.id() + "|" + email); }
    private String slot(String purpose, String subject) { return vault.digest("slot|" + server.id() + "|" + purpose + "|" + subject); }
    private AccountException invalid() { return new AccountException("RESET_VERIFICATION_FAILED", "验证未通过或已过期，请确认填写信息并重新获取验证码"); }

    /** 所有公开请求均计量候选邮箱，内部限额结果不披露账号关联。 */
    private boolean quota(String scope, int maximum, long duration) {
        long now = System.currentTimeMillis();
        String id = vault.digest(scope + "|" + server.id());
        Quota quota = em.find(Quota.class, id);
        if (quota == null) { quota = new Quota(); quota.setId(id); quota.setExpiresAt(0); em.persist(quota); }
        if (quota.getExpiresAt() <= now) {
            quota.setUsed(0);
            quota.setExpiresAt(duration == DAY ? ((now + 8 * HOUR) / DAY + 1) * DAY - 8 * HOUR : now + duration);
        }
        if (quota.getUsed() >= maximum) return false;
        quota.setUsed(quota.getUsed() + 1);
        return true;
    }

    private void requestLimit(String ip) {
        if (!quota("ip:" + ip, 20, HOUR)) throw new AccountException("RATE_LIMITED", "请求过于频繁，请稍后重试", 3600);
        long queued = em.createQuery("select count(o) from AccountEmailOutbox o where o.state < 2", Long.class).getSingleResult();
        if (queued >= config.getQueueLimit()) throw new AccountException("MAIL_UNAVAILABLE", "邮箱服务繁忙，请稍后重试");
    }

    private boolean sendLimits(String emailKey, Player player) {
        boolean permitted = quota("email-minute:" + emailKey, 1, MINUTE);
        permitted &= quota("email-hour:" + emailKey, 5, HOUR);
        permitted &= quota("email-day:" + emailKey, 10, DAY);
        if (player != null) {
            permitted &= quota("account-hour:" + player.getId(), 5, HOUR);
            permitted &= quota("account-day:" + player.getId(), 10, DAY);
        }
        permitted &= quota("global-hour", config.getHourlyLimit(), HOUR);
        permitted &= quota("global-day", config.getDailyLimit(), DAY);
        return permitted;
    }

    private boolean recoverable(Player p) {
        return p != null && !Boolean.TRUE.equals(p.getIsBot()) && !p.getUsername().startsWith("游客_")
                && !p.isBanned(System.currentTimeMillis()) && !p.deletionDue(System.currentTimeMillis())
                && (p.accountActive() || "PENDING_DELETION".equals(p.getAccountStatus()));
    }

    private Player current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal))
            throw new AccountException("ACCOUNT_UNAVAILABLE", "请使用正式账号重新登录");
        Player p = players.lockById(principal.getPlayerId()).orElseThrow(this::invalid);
        em.refresh(p, LockModeType.PESSIMISTIC_WRITE);
        if (!p.accountActive() || p.getAuthVersion() != principal.getAuthVersion()
                || Boolean.TRUE.equals(p.getIsBot()) || p.getUsername().startsWith("游客_"))
            throw new AccountException("ACCOUNT_UNAVAILABLE", "请使用正式账号重新登录");
        return p;
    }

    private void password(Player p, String password) {
        if (!quota("password:" + p.getId(), 10, HOUR))
            throw new AccountException("RATE_LIMITED", "密码验证过于频繁，请稍后再试", 3600);
        if (password == null || !passwords.matches(password, p.getPasswordHash()))
            throw new AccountException("PASSWORD_INVALID", "当前密码不正确");
    }

    private Map<String, Object> receipt(String id, long expiry) {
        long now = System.currentTimeMillis();
        return Map.of("accepted", true, "challengeId", id, "expiresAt", expiry,
                "resendAt", now + MINUTE, "serverNow", now,
                "message", "若账号信息符合条件，验证码将发送至该邮箱，请检查收件箱及垃圾箱");
    }

    private Map<String, Object> issue(String purpose, String subject, Player p, String username,
                                       String email, String flow, long deadline, boolean permitted) {
        String id = vault.token();
        long expiry = Math.min(System.currentTimeMillis() + 5 * MINUTE, deadline);
        if (!permitted) return receipt(id, expiry);
        String key = slot(purpose, subject);
        Challenge c = em.find(Challenge.class, key);
        boolean created = c == null;
        if (created) { c = new Challenge(); c.setSlot(key); }
        // 重发替换活动编号；即使旧邮件晚到，旧编号也不能再完成验证。
        c.setChallengeId(id); c.setPurpose(purpose); c.setPlayerId(p == null ? null : p.getId());
        c.setUsername(username); c.setEmailCipher(vault.encrypt(email)); c.setEmailKey(emailKey(email));
        c.setAuthVersion(p == null ? 0 : p.getAuthVersion()); c.setEmailVersion(p == null ? 0 : p.getRecoveryEmailVersion());
        c.setExpiresAt(expiry); c.setSent(false); c.setConsumed(false); c.setFailures(0); c.setFlowHash(flow);
        String code = vault.code();
        c.setCodeHash(vault.digest(id + "|" + purpose + "|" + c.getEmailKey() + "|" + code));
        if (created) em.persist(c);
        String action = switch (purpose) {
            case "REGISTER" -> "注册账号"; case "BIND" -> "绑定邮箱";
            case "RESET_PASSWORD" -> "重置密码"; default -> "更换邮箱";
        };
        enqueue(p == null ? null : p.getId(), id, email, action + "验证码",
                "您正在" + action + "，验证码：" + code + "。\n验证码自申请起 5 分钟内有效，请以页面截止时间为准。"
                        + "\n大区：" + server.name() + "。请勿向他人提供验证码；如非本人操作可忽略。", expiry);
        return receipt(id, expiry);
    }

    private Challenge verify(String purpose, String subject, Player p, String username, String email, String id, String code) {
        Challenge c = em.find(Challenge.class, slot(purpose, subject));
        if (c == null) throw invalid();
        if (c.isConsumed() || !c.isSent() || c.getFailures() >= 5 || System.currentTimeMillis() >= c.getExpiresAt()
                || !Objects.equals(id, c.getChallengeId()) || !Objects.equals(username, c.getUsername())
                || !Objects.equals(emailKey(email), c.getEmailKey())
                || (p != null && (!Objects.equals(c.getPlayerId(), p.getId()) || c.getAuthVersion() != p.getAuthVersion()
                || c.getEmailVersion() != p.getRecoveryEmailVersion())))
            throw invalid();
        if (!quota("verify:" + purpose + ":" + subject, 20, HOUR)) throw invalid();
        // 仅对当前有效流程中的错误验证码计数；等待投递或已结束的流程不消耗新验证码次数。
        if (code == null || !code.matches("[0-9]{6}")
                || !vault.matches(c.getCodeHash(), vault.digest(id + "|" + purpose + "|" + c.getEmailKey() + "|" + code))) {
            c.setFailures(c.getFailures() + 1);
            throw invalid();
        }
        return c;
    }

    public Map<String, Object> registrationCode(String username, String email, String ip) {
        String normalized = vault.normalize(email);
        if (username == null || username.isBlank() || username.length() < 3 || username.length() > 32)
            throw new AccountException("USERNAME_INVALID", "用户名需为 3—32 个字符");
        return execute(() -> {
            requestLimit(ip);
            boolean permitted = sendLimits(emailKey(normalized), null);
            permitted &= !players.existsByUsername(username) && !players.existsByRecoveryEmailKey(emailKey(normalized));
            return issue("REGISTER", emailKey(normalized), null, username, normalized, null, Long.MAX_VALUE, permitted);
        });
    }

    /** 创建账号与验证消费共享事务，不向旧注册重载提供免验证通道。 */
    public <T> T register(String username, String email, String id, String code, Supplier<T> create) {
        String normalized = vault.normalize(email);
        return execute(() -> {
            Challenge c = verify("REGISTER", emailKey(normalized), null, username, normalized, id, code);
            if (players.existsByUsername(username) || players.existsByRecoveryEmailKey(emailKey(normalized))) throw invalid();
            T result = create.get();
            Player p = players.findByUsername(username).orElseThrow(this::invalid);
            setEmail(p, normalized); c.setPlayerId(p.getId()); c.setConsumed(true);
            audit.record("system", "ACCOUNT_EMAIL_BIND", "PLAYER", p.getId().toString(), "大区：" + server.id(), null);
            notify(p, normalized, "邮箱绑定成功", "您的账号已绑定此邮箱，可在忘记密码时通过验证邮件设置新密码。");
            em.flush();
            return result;
        });
    }

    private Map<String, Object> info(Player p) {
        return Map.of("emailBound", p.getRecoveryEmailKey() != null, "maskedEmail",
                p.getRecoveryEmailCipher() == null ? "" : vault.mask(vault.decrypt(p.getRecoveryEmailCipher())),
                "verifiedAt", p.getRecoveryEmailVerifiedAt() == null ? 0L : p.getRecoveryEmailVerifiedAt());
    }
    public Map<String, Object> status() {
        // 未配置邮件也能查看未绑定状态；已绑定地址需要原加密密钥才能读取。
        return transactions.execute(s -> info(current()));
    }
    private void setEmail(Player p, String email) {
        p.setRecoveryEmailCipher(vault.encrypt(email)); p.setRecoveryEmailKey(emailKey(email));
        p.setRecoveryEmailVerifiedAt(System.currentTimeMillis()); p.setRecoveryEmailVersion(p.getRecoveryEmailVersion() + 1);
    }
    public Map<String, Object> bindCode(String password, String email, String ip) {
        String normalized = vault.normalize(email);
        return execute(() -> {
            requestLimit(ip); Player p = current(); password(p, password);
            if (p.getRecoveryEmailKey() != null) throw new AccountException("EMAIL_ALREADY_BOUND", "账号已绑定邮箱，请使用更换邮箱");
            if (players.existsByRecoveryEmailKey(emailKey(normalized))) throw new AccountException("EMAIL_UNAVAILABLE", "该邮箱不可用于绑定");
            boolean permitted = sendLimits(emailKey(normalized), p);
            if (!permitted) throw new AccountException("RATE_LIMITED", "验证码发送过于频繁，请稍后再试", 60);
            return issue("BIND", p.getId().toString(), p, p.getUsername(), normalized, null, Long.MAX_VALUE, true);
        });
    }
    public Map<String, Object> bind(String password, String email, String id, String code) {
        String normalized = vault.normalize(email);
        return execute(() -> {
            Player p = current(); password(p, password);
            Challenge c = verify("BIND", p.getId().toString(), p, p.getUsername(), normalized, id, code);
            if (p.getRecoveryEmailKey() != null || players.existsByRecoveryEmailKey(emailKey(normalized)))
                throw new AccountException("EMAIL_UNAVAILABLE", "该邮箱不可用于绑定");
            setEmail(p, normalized); c.setConsumed(true);
            audit.record("system", "ACCOUNT_EMAIL_BIND", "PLAYER", p.getId().toString(), "大区：" + server.id(), null);
            notify(p, normalized, "邮箱绑定成功", "您的账号已绑定此邮箱，可用于密码找回。");
            return info(p);
        });
    }
    public Map<String, Object> resetCode(String username, String email, String ip) {
        String normalized = vault.normalize(email);
        return execute(() -> {
            requestLimit(ip);
            Player found = players.findByUsername(username == null ? "" : username).orElse(null);
            Player p = found == null ? null : players.lockById(found.getId()).orElse(null);
            if (p != null) em.refresh(p, LockModeType.PESSIMISTIC_WRITE);
            boolean permitted = sendLimits(emailKey(normalized), p);
            permitted &= recoverable(p) && Objects.equals(emailKey(normalized), p == null ? null : p.getRecoveryEmailKey());
            String subject = p == null ? vault.digest("candidate|" + username) : p.getId().toString();
            return issue("RESET_PASSWORD", subject, p, username, normalized, null, Long.MAX_VALUE, permitted);
        });
    }
    public Map<String, Object> resetPassword(String username, String email, String id, String code, String newPassword, String ip) {
        String normalized = vault.normalize(email);
        AccountCredentialService.validatePassword(newPassword);
        return execute(() -> {
            if (!quota("reset-ip:" + ip, 30, HOUR)) throw new AccountException("RATE_LIMITED", "请稍后重试", 3600);
            Player found = players.findByUsername(username == null ? "" : username).orElse(null);
            if (found == null) throw invalid();
            Player p = players.lockById(found.getId()).orElseThrow(this::invalid);
            em.refresh(p, LockModeType.PESSIMISTIC_WRITE);
            if (!recoverable(p) || !Objects.equals(emailKey(normalized), p.getRecoveryEmailKey())) throw invalid();
            Challenge c = verify("RESET_PASSWORD", p.getId().toString(), p, username, normalized, id, code);
            credentials.changePassword(p, newPassword); c.setConsumed(true);
            audit.record("system", "ACCOUNT_PASSWORD_RESET", "PLAYER", p.getId().toString(), "大区：" + server.id(), null);
            notify(p, normalized, "密码重置成功", "您的登录密码已重置，所有设备已退出。请使用新密码重新登录；如非本人操作请联系官方支持。");
            return Map.of("success", true, "message", "密码已重置，所有设备已退出，请使用新密码登录");
        });
    }

    public Map<String, Object> oldCode(String password, String ip) {
        return execute(() -> {
            requestLimit(ip); Player p = current(); password(p, password);
            if (p.getRecoveryEmailCipher() == null) throw new AccountException("EMAIL_NOT_BOUND", "请先绑定邮箱");
            String email = vault.decrypt(p.getRecoveryEmailCipher());
            if (!sendLimits(p.getRecoveryEmailKey(), p)) throw new AccountException("RATE_LIMITED", "验证码发送过于频繁，请稍后再试", 60);
            return issue("CHANGE_OLD_EMAIL", p.getId().toString(), p, p.getUsername(), email, null, Long.MAX_VALUE, true);
        });
    }
    public Map<String, Object> authorizeChange(String password, String newEmail, String id, String code) {
        String normalized = vault.normalize(newEmail);
        return execute(() -> {
            Player p = current(); password(p, password);
            if (p.getRecoveryEmailCipher() == null) throw invalid();
            Challenge c = verify("CHANGE_OLD_EMAIL", p.getId().toString(), p, p.getUsername(), vault.decrypt(p.getRecoveryEmailCipher()), id, code);
            if (players.existsByRecoveryEmailKey(emailKey(normalized))) throw new AccountException("EMAIL_UNAVAILABLE", "该邮箱不可用于换绑");
            String token = vault.token(); Change change = new Change(); change.setTokenHash(vault.digest(token));
            change.setPlayerId(p.getId()); change.setEmailCipher(vault.encrypt(normalized)); change.setEmailKey(emailKey(normalized));
            change.setAuthVersion(p.getAuthVersion()); change.setEmailVersion(p.getRecoveryEmailVersion());
            change.setExpiresAt(System.currentTimeMillis() + 15 * MINUTE); em.persist(change); c.setConsumed(true);
            return Map.of("changeToken", token, "expiresAt", change.getExpiresAt());
        });
    }
    private Change change(Player p, String token) {
        if (token == null || token.length() > 64) throw invalid();
        Change c = em.find(Change.class, vault.digest(token));
        if (c == null || !c.getPlayerId().equals(p.getId()) || c.getAuthVersion() != p.getAuthVersion()
                || c.getEmailVersion() != p.getRecoveryEmailVersion() || c.getExpiresAt() <= System.currentTimeMillis()) throw invalid();
        return c;
    }
    public Map<String, Object> newCode(String token, String ip) {
        return execute(() -> {
            requestLimit(ip); Player p = current(); Change change = change(p, token);
            if (!sendLimits(change.getEmailKey(), p)) throw new AccountException("RATE_LIMITED", "验证码发送过于频繁，请稍后再试", 60);
            return issue("CHANGE_NEW_EMAIL", p.getId().toString(), p, p.getUsername(), vault.decrypt(change.getEmailCipher()),
                    change.getTokenHash(), change.getExpiresAt(), true);
        });
    }
    public Map<String, Object> completeChange(String token, String id, String code) {
        return execute(() -> {
            Player p = current(); Change change = change(p, token);
            String email = vault.decrypt(change.getEmailCipher());
            Challenge c = verify("CHANGE_NEW_EMAIL", p.getId().toString(), p, p.getUsername(), email, id, code);
            if (!Objects.equals(c.getFlowHash(), change.getTokenHash())) throw invalid();
            if (players.existsByRecoveryEmailKey(change.getEmailKey())) throw new AccountException("EMAIL_UNAVAILABLE", "该邮箱不可用于换绑");
            String old = vault.decrypt(p.getRecoveryEmailCipher());
            setEmail(p, email); credentials.invalidateSessions(p); c.setConsumed(true); em.remove(change);
            audit.record("system", "ACCOUNT_EMAIL_CHANGE", "PLAYER", p.getId().toString(), "大区：" + server.id(), null);
            notify(p, old, "邮箱已更换", "账号的绑定邮箱已更换；如非本人操作，请立即通过官方渠道联系支持。");
            notify(p, email, "邮箱绑定成功", "此邮箱现已成为账号的密码找回邮箱，所有设备已退出，请重新登录。");
            return Map.of("success", true, "message", "邮箱已更换，请重新登录");
        });
    }

    private void notify(Player p, String email, String title, String body) {
        enqueue(p.getId(), null, email, title, body, System.currentTimeMillis() + DAY);
    }
    private void enqueue(Long playerId, String challengeId, String email, String title, String body, long expiry) {
        Outbox mail = new Outbox(); mail.setId(vault.token()); mail.setPlayerId(playerId); mail.setChallengeId(challengeId);
        mail.setEmailCipher(vault.encrypt(email)); mail.setBodyCipher(vault.encrypt(body));
        mail.setSubject("【山河远征录·" + server.name() + "】" + title); mail.setExpiresAt(expiry);
        mail.setNextAttemptAt(System.currentTimeMillis()); em.persist(mail);
    }
}
