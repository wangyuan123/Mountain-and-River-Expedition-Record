package com.wargame.service;
import com.wargame.config.GameServerIdentity;
import com.wargame.config.RetentionPolicy;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import java.util.*;

/** 登录时才发放限时福利；不预建离线账号邮件，不补发已结束的活动。 */
@Service @RequiredArgsConstructor
public class MailGiftActivityService {
    private final MailGiftActivityRepository activities;
    private final MailGiftDeliveryRepository deliveries;
    private final MailService mails;
    private final AccountService accounts;
    private final GameServerIdentity server;
    private final RetentionPolicy policy;
    private static final Set<String> RESOURCES = Set.of("food", "steel", "oil", "rare", "gold", "diamond");

    /** 第一版只接受可直接领取的资源，避免未解锁兵种进入限时邮件。 */
    @Transactional
    public MailGiftActivity create(String id, long startsAt, long endsAt, boolean newOnly, String subject, String body, List<Map<String, Object>> rewards) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}") || activities.existsById(id)) throw new IllegalArgumentException("活动 ID 无效或已存在");
        if (startsAt >= endsAt || endsAt <= System.currentTimeMillis() || subject == null || subject.isBlank() || subject.length() > 80) throw new IllegalArgumentException("活动窗口或标题无效");
        if (rewards == null || rewards.isEmpty() || rewards.size() > 6) throw new IllegalArgumentException("奖励应包含 1 至 6 项资源");
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (var reward : rewards) {
            String type = String.valueOf(reward.get("type"));
            long qty;
            try { qty = Long.parseLong(String.valueOf(reward.get("qty"))); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("奖励数量必须为正整数"); }
            if (!RESOURCES.contains(type) || qty <= 0 || qty > 1_000_000_000) throw new IllegalArgumentException("限时福利只支持有效的资源奖励");
            snapshot.add(Map.of("type", type, "qty", qty));
        }
        MailGiftActivity a = new MailGiftActivity();
        a.setId(id); a.setServerId(server.id()); a.setStartsAt(startsAt); a.setEndsAt(endsAt);
        a.setClaimExpiresAt(endsAt + policy.days(policy.getGiftClaimDays()));
        a.setNoticeExpiresAt(a.getClaimExpiresAt() + policy.days(policy.getGiftNoticeDays()));
        a.setCleanupAfter(endsAt + policy.days(policy.getActivityHistoryDays()));
        a.setNewAccountsOnly(newOnly); a.setSubject(subject); a.setBody(body == null ? "" : body);
        a.setRewardsJson(JsonUtil.toJson(snapshot));
        return activities.save(a);
    }

    /** 账号→活动锁顺序，持锁后重查窗口；唯一凭据与邮件在同一事务提交。 */
    @Transactional
    public int issueOnLogin(Long playerId) {
        Player player = accounts.lockPlayer(playerId);
        if (!player.accountActive()) return 0;
        int issued = 0;
        long registeredAt = player.getCreatedAt() == null ? 0 : player.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        for (String id : activities.eligibleIds(server.id(), playerId, registeredAt, System.currentTimeMillis(), PageRequest.of(0, 100))) {
            var a = activities.lock(id).orElse(null);
            long now = System.currentTimeMillis();
            if (a == null || a.isClosed() || !server.id().equals(a.getServerId()) || now < a.getStartsAt() || now >= a.getEndsAt()) continue;
            // 玩家注册时间沿用服务器本地时区存储，资格比较显式转换为真实毫秒。
            if (a.isNewAccountsOnly() && (registeredAt < a.getStartsAt() || registeredAt >= a.getEndsAt())) continue;
            if (deliveries.existsByServerIdAndActivityIdAndPlayerId(server.id(), id, playerId)) continue;
            List<Map<String, Object>> rewards;
            try { rewards = JsonUtil.getMapper().readValue(a.getRewardsJson(), new TypeReference<>() {}); }
            catch (Exception e) { throw new IllegalStateException("活动奖励快照不可读取", e); }
            Mail mail = mails.sendSystem(playerId, "活动指挥部", "reward", a.getSubject(), a.getBody(), rewards, null, "TIMED_GIFT", "LOGIN_ACTIVITY", id, a.getClaimExpiresAt());
            if (mail == null) continue;
            mail.setCleanupAfter(a.getNoticeExpiresAt()); mail.setRetentionExpiresAt(a.getNoticeExpiresAt());
            MailGiftDelivery delivery = new MailGiftDelivery();
            delivery.setServerId(server.id()); delivery.setActivityId(id); delivery.setPlayerId(playerId);
            delivery.setMailId(mail.getId()); delivery.setIssuedAt(now); deliveries.saveAndFlush(delivery);
            issued++;
        }
        return issued;
    }

    @Transactional
    public void close(String id) {
        var a = activities.lock(id).orElseThrow(() -> new IllegalArgumentException("活动不存在"));
        a.setClosed(true);
    }
}
