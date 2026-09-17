package com.wargame.service;

import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.security.AccountException;
import com.wargame.security.RateLimiter;
import com.wargame.util.JwtUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 注销、显式恢复和到期清理共用玩家行锁，期限与会话版本均持久化。 */
@Service
@RequiredArgsConstructor
public class AccountService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.compliance.AntiAddictionService protection;
    private final PlayerRepository players;
    private final PlayerCityRepository cities;
    private final GuildMemberRepository members;
    private final GuildRepository guilds;
    private final EntityManager em;
    private final org.springframework.security.crypto.password.PasswordEncoder passwords;
    private final JwtUtil jwt;
    private final RateLimiter limiter;
    private final WebSocketPushService push;

    @Value("${game.account.cooldown-days:7}")
    private int cooldownDays = 7;

    public int getCooldownDays() { return Math.max(1, cooldownDays); }

    /** 锁后刷新，避免同一请求的鉴权读取或 MySQL 快照遮蔽最新注销状态。 */
    public Player lockPlayer(Long id) {
        Player player = players.lockById(id).orElseThrow(() -> new AccountException("ACCOUNT_UNAVAILABLE", "账号不存在"));
        em.flush();
        em.refresh(player, LockModeType.PESSIMISTIC_WRITE);
        return player;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preview(Long id) {
        Player player = players.findById(id).orElseThrow();
        requireFormal(player);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", player.getUsername());
        result.put("cooldownDays", getCooldownDays());
        result.put("cityCount", cities.findByOwnerId(id).size());
        result.put("blocker", guildBlocker(id));
        result.put("singleMemberGuild", members.findByPlayerId(id)
                .map(m -> "leader".equals(m.getRole()) && members.countByGuildId(m.getGuildId()) == 1).orElse(false));
        return result;
    }

    private void requireFormal(Player player) {
        if (player.getUsername().startsWith("游客_")) throw new AccountException("GUEST_ACCOUNT", "游客模式不支持账号注销");
    }

    private String guildBlocker(Long id) {
        return members.findByPlayerId(id).filter(m -> "leader".equals(m.getRole()))
                .filter(m -> members.countByGuildId(m.getGuildId()) > 1)
                .map(m -> "请先在军团管理中转让团长，再申请注销").orElse("");
    }

    /** 申请只改变账号访问权限，恢复期内仍按正常离线战局结算。 */
    @Transactional
    public Map<String, Object> disableAccount(Long id, String password, String confirm, String requestId) {
        Player player = lockPlayer(id);
        requireFormal(player);
        limit("DELETE:" + id, 5, 60_000);
        if (password == null || !passwords.matches(password, player.getPasswordHash()))
            throw new AccountException("PASSWORD_INVALID", "当前密码不正确");
        if (!"确认注销".equals(confirm == null ? "" : confirm.trim()))
            throw new AccountException("CONFIRM_REQUIRED", "请输入“确认注销”以完成操作");
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{8,64}"))
            throw new AccountException("REQUEST_INVALID", "请求编号无效，请重新打开注销页面");
        if ("PENDING_DELETION".equals(player.getAccountStatus())) return status(player);
        if (!player.accountActive()) throw new AccountException("ACCOUNT_UNAVAILABLE", "账号不可用");
        String blocker = guildBlocker(id);
        if (!blocker.isEmpty()) throw new AccountException("GUILD_TRANSFER_REQUIRED", blocker);
        long now = System.currentTimeMillis();
        player.setAccountStatus("PENDING_DELETION");
        player.setDisabled(1);
        player.setDisabledAt(now);
        player.setRecoverUntil(now + getCooldownDays() * 86_400_000L);
        player.setDeletionRequestId(requestId);
        player.setAuthVersion(player.getAuthVersion() + 1);
        clearRecovery(player);
        players.saveAndFlush(player);
        push.disconnectAccountAfterCommit(id);
        return status(player);
    }

    /** 状态查询从不恢复账号；同一截止时间同时用于 UI 和服务端判定。 */
    public Map<String, Object> status(Player player) {
        Map<String, Object> result = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        result.put("status", player.deletionDue(now) ? "RECOVERY_EXPIRED" : player.getAccountStatus());
        result.put("disabledAt", player.getDisabledAt());
        result.put("recoverUntil", player.getRecoverUntil());
        result.put("cooldownDays", getCooldownDays());
        result.put("serverNow", now);
        return result;
    }

    /** 密码已验证后签发一次性恢复凭据；数据库仅存 SHA-256 摘要。 */
    public Map<String, Object> recoveryRequired(Player player) {
        long now = System.currentTimeMillis();
        if (player.deletionDue(now)) throw new AccountException("RECOVERY_EXPIRED", "该账号已超过恢复期限，无法恢复");
        String secret = player.getId() + "." + UUID.randomUUID() + UUID.randomUUID().toString();
        player.setRecoveryTokenHash(hash(secret));
        player.setRecoveryTokenExpiresAt(Math.min(player.getRecoverUntil(), now + 300_000));
        players.save(player);
        Map<String, Object> result = new LinkedHashMap<>(status(player));
        result.put("status", "RECOVERY_REQUIRED");
        result.put("recoveryToken", secret);
        result.put("username", player.getUsername());
        return result;
    }

    /** 恢复与清理竞争同一行锁；到期等号归清理方，旧凭据永久失效。 */
    @Transactional
    public Map<String, Object> recover(String token, boolean confirm) {
        if (!confirm) throw new AccountException("CONFIRM_REQUIRED", "请明确确认恢复账号");
        Long id;
        try { id = Long.valueOf(token.substring(0, token.indexOf('.'))); }
        catch (RuntimeException e) { throw new AccountException("RECOVERY_INVALID", "恢复凭据无效，请重新登录验证"); }
        limit("RECOVER:" + id, 10, 60_000);
        Player player = lockPlayer(id);
        long now = System.currentTimeMillis();
        if (player.getRecoveryTokenHash() == null || !MessageDigest.isEqual(
                hash(token).getBytes(StandardCharsets.UTF_8), player.getRecoveryTokenHash().getBytes(StandardCharsets.UTF_8)))
            throw new AccountException("RECOVERY_INVALID", "恢复凭据无效，请重新登录验证");
        if (player.deletionDue(now)) throw new AccountException("RECOVERY_EXPIRED", "该账号已超过恢复期限，无法恢复");
        if (!"PENDING_DELETION".equals(player.getAccountStatus()) || now >= player.getRecoveryTokenExpiresAt())
            throw new AccountException("RECOVERY_INVALID", "恢复凭据已失效，请重新登录验证");
        player.setAccountStatus("ACTIVE");
        player.setDisabled(0);
        player.setDisabledAt(0L);
        player.setRecoverUntil(0L);
        player.setDeletionRequestId(null);
        player.setAuthVersion(player.getAuthVersion() + 1);
        clearRecovery(player);
        players.saveAndFlush(player);
        return Map.of("token", jwt.generateToken(player.getUsername(), id, player.getAuthVersion()),
                "username", player.getUsername(), "playerId", id);
    }

    public void limit(String key, int count, long window) {
        if (!limiter.allow(key, count, window)) {
            long retry = limiter.retryAfterSeconds(key, window);
            throw new AccountException("RATE_LIMITED", "操作过于频繁，请在 " + retry + " 秒后重试", retry);
        }
    }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private void clearRecovery(Player player) {
        player.setRecoveryTokenHash(null);
        player.setRecoveryTokenExpiresAt(0L);
    }

    /** 每个账号独立事务；不删除其他玩家的行军、邮件或已经提交的战果。 */
    @Transactional
    public void purgeExpiredAccount(Long id) {
        Player player = lockPlayer(id);
        long now = System.currentTimeMillis();
        if (!"PENDING_DELETION".equals(player.getAccountStatus()) || !player.deletionDue(now)) return;

        members.findByPlayerId(id).ifPresent(member -> {
            if ("leader".equals(member.getRole())) {
                Guild guild = guilds.findById(member.getGuildId()).orElseThrow();
                List<GuildMember> others = members.findByGuildIdOrderByJoinedAtAsc(guild.getId()).stream()
                        .filter(m -> !id.equals(m.getPlayerId())).toList();
                // 兼容旧版已注销但仍为团长的账号，不连带删除其他成员。
                if (!others.isEmpty()) {
                    GuildMember successor = others.get(0);
                    successor.setRole("leader");
                    guild.setLeaderPlayerId(successor.getPlayerId());
                    members.save(successor);
                    guilds.save(guild);
                } else {
                    em.createQuery("delete from GuildApplication a where a.guildId = :id").setParameter("id", guild.getId()).executeUpdate();
                    members.delete(member);
                    members.flush();
                    guilds.delete(guild);
                }
            }
        });
        em.flush();
        for (String entity : List.of("GuildApplication", "GuildMember", "OfficerEquipment", "ArmyProductionQueue",
                "TechResearchQueue", "WoundedUnit", "Building", "ArmyUnit", "Fortification", "Technology", "Officer", "Construction",
                "Resources", "CityState", "Academy", "PlayerItem", "ScoutReport", "PlayerQuest", "PlayerGuide", "March", "ChatMessage")) {
            em.createQuery("delete from " + entity + " e where e.playerId = :id").setParameter("id", id).executeUpdate();
        }
        em.createQuery("delete from IncomingMarch m where m.targetPlayerId = :id").setParameter("id", id).executeUpdate();
        em.createQuery("delete from Mail m where m.toPlayerId = :id").setParameter("id", id).executeUpdate();
        em.createQuery("update Mail m set m.fromName = '已注销玩家' where m.fromPlayerId = :id").setParameter("id", id).executeUpdate();
        em.createQuery("update WildTile w set w.occupied = false, w.occupiedBy = null, w.garrison = '{}', "
                + "w.gathering = false, w.gatherStartAt = 0, w.gatherEndAt = 0, w.gatherLoad = 0, w.gatherRes = null, w.version = w.version + 1 "
                + "where w.occupiedBy = :id").setParameter("id", id).executeUpdate();
        em.createQuery("update Player p set p.warAgainstId = null, p.warAt = 0, p.warEndAt = 0, p.version = p.version + 1 "
                + "where p.warAgainstId = :id").setParameter("id", id).executeUpdate();
        em.createQuery("delete from PlayerCity c where c.ownerId = :id").setParameter("id", id).executeUpdate();
        player.setActiveCityId(null);
        player.setCityPosX(null); player.setCityPosY(null);
        player.setPosX(null); player.setPosY(null);
        player.setCityName(""); player.setAvatar("");
        player.setPrestige(0); player.setLevel(0); player.setMilitaryRank(0); player.setVipLevel(0);
        player.setCivilianPopulation(0); player.setPopulationGrowthRemainder(0.0);
        player.setWarAgainstId(null); player.setWarAt(0L); player.setWarEndAt(0L);
        player.setTax(0); player.setMorale(0); player.setResentment(0); player.setLastAppeaseAt(0L);
        // 保留用户名仅用于占用检查，密码替换为无法通过 BCrypt 验证的墓碑值。
        protection.deleteAccount(id);
        player.setPasswordHash("!deleted");
        player.setAccountStatus("DELETED"); player.setDisabled(1); player.setDeletedAt(now);
        player.setAuthVersion(player.getAuthVersion() + 1);
        clearRecovery(player);
        players.saveAndFlush(player);
        push.disconnectAccountAfterCommit(id);
    }
}
