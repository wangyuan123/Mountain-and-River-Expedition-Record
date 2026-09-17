package com.wargame.service.compliance;

import com.wargame.config.ComplianceProperties;
import com.wargame.model.compliance.ComplianceRecords.*;
import com.wargame.model.entity.Player;
import com.wargame.repository.PlayerRepository;
import com.wargame.security.GameAccessException;
import com.wargame.security.RateLimiter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** 实名主体共享日额度与活动会话；后台世界结算不调用本服务的准入方法。 */
@Service
@RequiredArgsConstructor
public class AntiAddictionService {
    private final EntityManager em;
    private final PlayerRepository players;
    private final ComplianceProperties config;
    private final IdentityVault vault;
    private final RealNameProvider provider;
    private final PlayCalendar calendar;
    private final Clock clock;
    private final RateLimiter limiter;

    public boolean enabled() { return config.isEnabled(); }
    public boolean fixtures() { return config.isLocalFixtures(); }

    /** 新老账号都须通过服务端核验；已绑定主体不能用另一个人的凭据覆盖。 */
    @Transactional
    public Map<String, Object> verify(Player player, String proof) {
        if (!limiter.allow("IDENTITY:" + player.getId(), 5, 60_000L))
            throw new GameAccessException("RATE_LIMITED", "提交过于频繁，请稍后重试", 429);
        if (proof == null || proof.length() > 2048) throw new IllegalArgumentException("核验凭据无效");
        if (config.getDataKey().isBlank()) throw new GameAccessException("IDENTITY_UNAVAILABLE", "实名服务尚未配置", 503);
        var result = provider.verify(proof, player.getId());
        long now = clock.millis();
        LocalDate today = calendar.date(now);
        if (result == null || result.subjectReference() == null || result.subjectReference().isBlank()
                || result.birthDate() == null || result.birthDate().isAfter(today)
                || result.birthDate().isBefore(today.minusYears(150)) || result.validUntil() <= now
                || (result.consentVersion() != null && result.consentVersion().length() > 80))
            throw new GameAccessException("IDENTITY_REJECTED", "未取得有效实名核验结果", 400);
        int age = Period.between(result.birthDate(), today).getYears();
        if (age < 14 && (result.guardianReference() == null || result.guardianReference().isBlank()
                || result.consentVersion() == null || result.consentVersion().isBlank()))
            throw new GameAccessException("GUARDIAN_CONSENT_REQUIRED", "请先通过核验服务完成监护关系和儿童个人信息授权");
        String id = vault.subjectId(result.subjectReference());
        Binding binding = em.find(Binding.class, player.getId());
        if (binding != null && !binding.getSubjectId().equals(id))
            throw new GameAccessException("IDENTITY_CHANGE_REQUIRES_REVIEW", "身份更正需要申诉审核，不能自行换绑");
        Subject subject = lockSubject(id);
        if (subject == null) {
            subject = new Subject(); subject.setId(id);
            subject.setBirthCipher(vault.encrypt(result.birthDate().toString()));
            em.persist(subject);
        } else if (!vault.decrypt(subject.getBirthCipher()).equals(result.birthDate().toString())) {
            throw new GameAccessException("IDENTITY_CHANGE_REQUIRES_REVIEW", "年龄信息变更需要审核");
        }
        subject.setVerifiedUntil(result.validUntil());
        subject.setUpdatedAt(now);
        // 只有可信核验结果可建立监护关系，儿童或其他玩家自报的关系不被采信。
        if (result.guardianReference() != null && !result.guardianReference().isBlank()) {
            String guardian = vault.subjectId(result.guardianReference());
            if (guardian.equals(id)) throw new GameAccessException("IDENTITY_REJECTED", "监护关系核验失败", 400);
            subject.setGuardianId(guardian);
            subject.setConsentVersion(result.consentVersion());
        } else {
            // 重新核验未确认旧关系时撤销旧授权，不能继续沿用历史监护权限。
            subject.setGuardianId(null); subject.setConsentVersion(null);
        }
        if (binding == null) {
            binding = new Binding(); binding.setPlayerId(player.getId()); binding.setSubjectId(id); em.persist(binding);
        }
        binding.setVerifiedAt(now);
        event(player.getId(), "IDENTITY_VERIFIED");
        em.flush();
        return status(player.getId(), null);
    }

    private Subject subject(Long playerId) {
        Binding binding = em.find(Binding.class, playerId);
        return binding == null ? null : em.find(Subject.class, binding.getSubjectId());
    }
    private Subject lockSubject(String id) {
        // 刷新锁内实体前保存本事务的结算/监护变更，避免 refresh 丢弃待写入状态。
        em.flush();
        Subject subject = em.find(Subject.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (subject != null) em.refresh(subject, LockModeType.PESSIMISTIC_WRITE);
        return subject;
    }
    private Subject requireSubject(Long playerId, boolean lock) {
        Binding binding = em.find(Binding.class, playerId);
        Subject subject = binding == null ? null : (lock ? lockSubject(binding.getSubjectId()) : em.find(Subject.class, binding.getSubjectId()));
        if (subject == null) throw new GameAccessException("REAL_NAME_REQUIRED", "请先完成实名认证");
        if (subject.getVerifiedUntil() <= clock.millis()) throw new GameAccessException("REAL_NAME_REQUIRED", "实名核验已失效，请重新核验");
        return subject;
    }
    private int age(Subject subject, long now) { return Period.between(LocalDate.parse(vault.decrypt(subject.getBirthCipher())), calendar.date(now)).getYears(); }
    private boolean minor(Subject subject, long now) { return age(subject, now) < 18; }
    private Session active(Subject subject) { return subject.getActiveSession() == null ? null : em.find(Session.class, subject.getActiveSession()); }
    private long pending(Session session, long now) {
        return session == null || session.getEndedAt() != 0 ? 0 : Math.max(0, Math.min(now, Math.min(session.getLeaseUntil(), session.getAllowedUntil())) - session.getAccountedThrough());
    }
    private long used(Subject subject, long now) {
        Usage usage = em.find(Usage.class, subject.getId() + ":" + calendar.date(now));
        Session session = active(subject);
        return (usage == null ? 0 : usage.getUsedMillis()) +
                (session != null && calendar.date(session.getAccountedThrough()).equals(calendar.date(now)) ? pending(session, now) : 0);
    }

    /** 在主体锁内结算服务区间，多标签与重连不会重复记时；已失联的会话最多计至租约末尾。 */
    private void settle(Subject subject, long now) {
        Session session = active(subject);
        if (session == null) return;
        long delta = pending(session, now);
        if (delta > 0) {
            String date = calendar.date(session.getAccountedThrough()).toString();
            String id = subject.getId() + ":" + date;
            Usage usage = em.find(Usage.class, id);
            if (usage == null) {
                usage = new Usage(); usage.setId(id); usage.setSubjectId(subject.getId()); usage.setPlayDate(date); em.persist(usage);
            }
            usage.setUsedMillis(usage.getUsedMillis() + delta);
            session.setAccountedThrough(session.getAccountedThrough() + delta);
        }
    }

    private long permitUntil(Subject subject, long now) {
        if (subject.getVerifiedUntil() <= now) throw new GameAccessException("REAL_NAME_REQUIRED", "请重新完成实名认证");
        LocalDate day = calendar.date(now);
        // 成年会话同样按北京时间午夜截断，避免一个服务区间横跨日账本。
        if (!minor(subject, now)) return Math.min(subject.getVerifiedUntil(), calendar.at(day.plusDays(1), 0));
        if (age(subject, now) < 14 && (subject.getGuardianId() == null || subject.getConsentVersion() == null))
            throw new GameAccessException("GUARDIAN_CONSENT_REQUIRED", "请完成监护授权");
        if (subject.isPaused()) throw new GameAccessException("GUARDIAN_RESTRICTED", "监护人已暂停游戏");
        if (!calendar.covered(day)) throw new GameAccessException("CALENDAR_UNAVAILABLE", "游戏开放日历暂不可用，请稍后再试", 503);
        long end = calendar.at(day, Math.min(1260, subject.getEndMinute()));
        if (!calendar.open(day) || now < calendar.at(day, 1200) || now >= end)
            throw new GameAccessException("PLAY_WINDOW_CLOSED", "当前不在允许的游戏时段");
        long remaining = Math.min(3600, subject.getDailyLimitSeconds()) * 1000L - used(subject, now);
        if (remaining <= 0) throw new GameAccessException("PLAY_TIME_EXHAUSTED", "今日游戏时间已用完");
        return Math.min(subject.getVerifiedUntil(), Math.min(end, now + remaining));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(Long playerId, String secret) {
        long now = clock.millis();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", enabled()); data.put("serverNow", now); data.put("policyVersion", config.getPolicyVersion());
        data.put("localFixtures", fixtures()); data.put("providerAvailable", provider.available() && !config.getDataKey().isBlank());
        String url = provider.authorizationUrl();
        data.put("authorizationUrl", url != null && url.startsWith("https://") ? url : "");
        data.put("supportUrl", config.getSupportUrl()); data.put("nextWindowStart", calendar.nextWindow(now));
        data.put("canPlay", false); data.put("sessionActive", false); data.put("allowedUntil", 0L); data.put("remainingSeconds", 0L);
        data.put("code", "REAL_NAME_REQUIRED"); data.put("message", "请先完成实名认证"); data.put("identityStatus", "UNVERIFIED");
        data.put("realPaymentEnabled", false);
        if (!enabled()) { data.put("canPlay", true); data.put("sessionActive", true); data.put("code", "ALLOWED"); return data; }
        Subject subject = subject(playerId);
        if (subject == null) return data;
        data.put("identityStatus", subject.getVerifiedUntil() > now ? "VERIFIED" : "EXPIRED");
        data.put("minor", minor(subject, now)); data.put("usedSeconds", used(subject, now) / 1000);
        data.put("guardianBound", subject.getGuardianId() != null);
        data.put("dailyLimitSeconds", subject.getDailyLimitSeconds()); data.put("endMinute", subject.getEndMinute());
        data.put("paused", subject.isPaused()); data.put("chatAllowed", subject.isChatAllowed());
        try {
            long until = permitUntil(subject, now);
            data.put("canPlay", true); data.put("allowedUntil", until); data.put("remainingSeconds", (until - now) / 1000);
            data.put("code", "ALLOWED"); data.put("message", "当前可以进入游戏");
            Session session = active(subject);
            Player player = players.findById(playerId).orElseThrow();
            boolean valid = matches(session, subject, player, secret, now);
            data.put("sessionActive", valid);
            if (valid) data.put("leaseUntil", session.getLeaseUntil());
        } catch (GameAccessException e) { data.put("code", e.getCode()); data.put("message", e.getMessage()); }
        return data;
    }

    private boolean matches(Session session, Subject subject, Player player, String secret, long now) {
        return session != null && session.getId().equals(IdentityVault.sessionId(secret))
                && session.getId().equals(subject.getActiveSession()) && session.getPlayerId().equals(player.getId())
                && player.accountActive() && session.getAuthVersion() == player.getAuthVersion()
                && session.getEndedAt() == 0 && now < session.getLeaseUntil() && now < session.getAllowedUntil();
    }

    @Transactional
    public Map<String, Object> start(Player player, String secret) {
        if (!enabled()) return status(player.getId(), secret);
        String id = IdentityVault.sessionId(secret);
        if (id.isEmpty()) throw new IllegalArgumentException("游戏会话编号无效");
        Subject subject = requireSubject(player.getId(), true);
        long now = clock.millis(); settle(subject, now);
        long until = permitUntil(subject, now);
        Session existing = em.find(Session.class, id);
        if (existing != null) {
            if (!matches(existing, subject, player, secret, now)) throw new GameAccessException("PLAY_SESSION_EXPIRED", "请重新申请游戏会话");
            return renew(player, secret);
        }
        Session previous = active(subject);
        if (previous != null && previous.getEndedAt() == 0) {
            previous.setEndedAt(now); previous.setLeaseUntil(Math.min(now, previous.getLeaseUntil()));
            event(previous.getPlayerId(), "PLAY_SESSION_REPLACED");
        }
        Session session = new Session(); session.setId(id); session.setSubjectId(subject.getId()); session.setPlayerId(player.getId());
        session.setAuthVersion(player.getAuthVersion()); session.setStartedAt(now); session.setAccountedThrough(now);
        session.setAllowedUntil(until); session.setLeaseUntil(Math.min(until, now + 60_000L)); em.persist(session);
        subject.setActiveSession(id); event(player.getId(), "PLAY_SESSION_STARTED"); em.flush();
        return status(player.getId(), secret);
    }

    @Transactional
    public Map<String, Object> renew(Player player, String secret) {
        if (!enabled()) return status(player.getId(), secret);
        Subject subject = requireSubject(player.getId(), true);
        long now = clock.millis();
        Session session = active(subject);
        if (!matches(session, subject, player, secret, now)) throw new GameAccessException("PLAY_SESSION_EXPIRED", "游戏会话已结束，请重新进入");
        settle(subject, now);
        long until = Math.min(session.getAllowedUntil(), permitUntil(subject, now));
        session.setAllowedUntil(until); session.setLeaseUntil(Math.min(until, now + 60_000L));
        em.flush(); return status(player.getId(), secret);
    }

    /** HTTP 操作持有主体锁至业务事务结束，与换设备、监护调整及用时结算互斥。 */
    @Transactional
    public void requireOperationAccess(Player player, String secret, String path) {
        if (enabled()) requireSubject(player.getId(), true);
        requireAccess(player, secret, path);
    }

    /** WebSocket 收发与 HTTP 提交前检查；推送仅只读校验，避免跨玩家推送获取嵌套主体锁。 */
    @Transactional(readOnly = true)
    public void requireAccess(Player player, String secret, String path) {
        if (!enabled()) return;
        Subject subject = requireSubject(player.getId(), false);
        long now = clock.millis(); permitUntil(subject, now);
        if (!matches(active(subject), subject, player, secret, now)) throw new GameAccessException("PLAY_SESSION_EXPIRED", "游戏会话已结束，请重新进入");
        if (minor(subject, now) && !subject.isChatAllowed() && (path.contains("/chat/") || path.endsWith("/mail/send") || path.endsWith("/guild/notice")))
            throw new GameAccessException("CHAT_RESTRICTED", "监护人已限制聊天与消息发送");
    }

    @Transactional
    public void end(Player player, String secret) {
        Subject subject = subject(player.getId());
        if (subject == null) return;
        subject = lockSubject(subject.getId());
        Session session = active(subject);
        if (session == null || !session.getPlayerId().equals(player.getId()) || !session.getId().equals(IdentityVault.sessionId(secret))) return;
        long now = clock.millis(); settle(subject, now);
        if (session.getEndedAt() == 0) { session.setEndedAt(now); session.setLeaseUntil(Math.min(now, session.getLeaseUntil())); event(player.getId(), "PLAY_SESSION_ENDED"); }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> children(Long playerId) {
        Subject guardian = requireSubject(playerId, false);
        if (minor(guardian, clock.millis())) throw new GameAccessException("GUARDIAN_REQUIRED", "请使用已核验的成年监护人账号");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Subject child : em.createQuery("select s from IdentitySubject s where s.guardianId = :id", Subject.class).setParameter("id", guardian.getId()).getResultList()) {
            if (!minor(child, clock.millis())) continue;
            List<Binding> bindings = em.createQuery("select b from PlayerIdentity b where b.subjectId = :id", Binding.class).setParameter("id", child.getId()).getResultList();
            for (Binding binding : bindings) {
                Player player = players.findById(binding.getPlayerId()).orElse(null);
                if (player == null || !player.accountActive()) continue;
                result.add(Map.of("playerId", player.getId(), "username", player.getUsername(), "dailyLimitSeconds", child.getDailyLimitSeconds(),
                        "endMinute", child.getEndMinute(), "paused", child.isPaused(), "chatAllowed", child.isChatAllowed(),
                        "usedSeconds", used(child, clock.millis()) / 1000, "paidAmountFen", 0));
            }
        }
        return result;
    }

    /** 已验证监护人只能在法定上限内调整规则；关系来自核验提供者，不能通过本接口自建。 */
    @Transactional
    public void restrict(Long guardianPlayerId, Long childPlayerId, int seconds, int endMinute, boolean paused, boolean chatAllowed) {
        Subject guardian = requireSubject(guardianPlayerId, false);
        Subject child = requireSubject(childPlayerId, true);
        if (minor(guardian, clock.millis()) || !minor(child, clock.millis()) || !guardian.getId().equals(child.getGuardianId()))
            throw new GameAccessException("GUARDIAN_REQUIRED", "没有该账号的监护权限");
        if (seconds < 0 || seconds > 3600 || endMinute < 1200 || endMinute > 1260) throw new IllegalArgumentException("时间限制必须在20:00—21:00及每日60分钟以内");
        settle(child, clock.millis());
        child.setDailyLimitSeconds(seconds); child.setEndMinute(endMinute); child.setPaused(paused); child.setChatAllowed(chatAllowed);
        child.setUpdatedAt(clock.millis()); event(childPlayerId, "GUARDIAN_RESTRICTION_CHANGED");
    }

    @Transactional
    public Map<String, Object> requestHelp(Long playerId, String kind, String id) {
        if (!Set.of("IDENTITY", "GUARDIAN", "REFUND", "PRIVACY", "REPORT").contains(kind == null ? "" : kind)) throw new IllegalArgumentException("请选择申请类型");
        try { UUID.fromString(id); } catch (RuntimeException e) { throw new IllegalArgumentException("申请编号无效"); }
        SupportRequest existing = em.find(SupportRequest.class, id);
        if (existing != null) {
            if (!existing.getPlayerId().equals(playerId) || !existing.getRequestType().equals(kind)) throw new IllegalArgumentException("申请编号已使用");
            return Map.of("id", existing.getId(), "status", existing.getStatus());
        }
        if (!limiter.allow("PROTECTION:" + playerId, 5, 3_600_000L)) throw new GameAccessException("RATE_LIMITED", "申请过于频繁，请稍后再试", 429);
        SupportRequest request = new SupportRequest(); request.setId(id); request.setPlayerId(playerId); request.setRequestType(kind); request.setCreatedAt(clock.millis());
        em.persist(request); event(playerId, "PROTECTION_REQUESTED");
        return Map.of("id", id, "status", "RECEIVED");
    }

    @Transactional(readOnly = true)
    public List<SupportRequest> requests(Long playerId) {
        return em.createQuery("select r from ProtectionRequest r where r.playerId = :id order by r.createdAt desc", SupportRequest.class)
                .setParameter("id", playerId).setMaxResults(20).getResultList();
    }

    @Transactional(readOnly = true)
    public long treatmentDeadline(Long playerId, long original) {
        Subject subject = subject(playerId);
        return subject != null && minor(subject, clock.millis()) ? calendar.treatmentDeadline(original) : original;
    }

    /** 注销清除账号关联；当前日主体账本保留至次日，防止同日重新注册重置用时。 */
    @Transactional
    public void deleteAccount(Long playerId) {
        Subject subject = subject(playerId);
        if (subject != null) {
            subject = lockSubject(subject.getId());
            settle(subject, clock.millis());
            Session session = active(subject);
            if (session != null && session.getPlayerId().equals(playerId)) subject.setActiveSession(null);
            subject.setUpdatedAt(clock.millis());
        }
        Binding binding = em.find(Binding.class, playerId);
        if (binding != null) em.remove(binding);
        em.createQuery("delete from PlaySession s where s.playerId = :id").setParameter("id", playerId).executeUpdate();
        em.createQuery("update ComplianceEvent e set e.playerId = null where e.playerId = :id").setParameter("id", playerId).executeUpdate();
        em.createQuery("delete from ProtectionRequest r where r.playerId = :id").setParameter("id", playerId).executeUpdate();
    }

    /** 有界维护：补记失联租约、标记结束并按已公布的开发默认期限清理去标识化记录。 */
    @Transactional
    public void maintain() {
        long now = clock.millis();
        for (String id : em.createQuery("select s.subjectId from PlaySession s where s.endedAt = 0 and (s.leaseUntil <= :now or s.allowedUntil <= :now)", String.class)
                .setParameter("now", now).setMaxResults(100).getResultList()) {
            Subject subject = lockSubject(id);
            if (subject == null) continue;
            Session session = active(subject);
            if (session == null || session.getEndedAt() != 0 || Math.min(session.getLeaseUntil(), session.getAllowedUntil()) > now) continue;
            settle(subject, now); session.setEndedAt(Math.min(session.getLeaseUntil(), session.getAllowedUntil()));
            event(session.getPlayerId(), "PLAY_SESSION_EXPIRED");
        }
        long startOfDay = calendar.at(calendar.date(now), 0);
        for (String id : em.createQuery("select s.id from IdentitySubject s where s.updatedAt < :before and not exists (select b.playerId from PlayerIdentity b where b.subjectId = s.id)", String.class)
                .setParameter("before", startOfDay).setMaxResults(100).getResultList()) {
            Subject subject = lockSubject(id);
            Long count = em.createQuery("select count(b) from PlayerIdentity b where b.subjectId = :id", Long.class).setParameter("id", id).getSingleResult();
            if (count != 0 || subject == null || subject.getUpdatedAt() >= startOfDay) continue;
            em.createQuery("delete from PlaySession s where s.subjectId = :id").setParameter("id", id).executeUpdate();
            em.createQuery("delete from PlayUsage u where u.subjectId = :id").setParameter("id", id).executeUpdate();
            em.remove(subject);
        }
        // 这些是开发默认数据政策，不是声称法律对所有记录统一规定了该期限。
        em.createQuery("delete from PlaySession s where s.endedAt > 0 and s.endedAt < :before").setParameter("before", now - 30L * 86_400_000L).executeUpdate();
        em.createQuery("delete from PlayUsage u where u.playDate < :before").setParameter("before", calendar.date(now).minusDays(30).toString()).executeUpdate();
        em.createQuery("delete from ComplianceEvent e where e.createdAt < :before").setParameter("before", now - 180L * 86_400_000L).executeUpdate();
        em.createQuery("delete from ProtectionRequest r where r.status = 'RESOLVED' and r.createdAt < :before").setParameter("before", now - 180L * 86_400_000L).executeUpdate();
    }

    private void event(Long playerId, String type) {
        Event event = new Event(); event.setPlayerId(playerId); event.setEventType(type); event.setPolicyVersion(config.getPolicyVersion()); event.setCreatedAt(clock.millis()); em.persist(event);
    }
}
