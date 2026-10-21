package com.wargame.service;

import com.wargame.config.RetentionPolicy;
import com.wargame.model.entity.*;
import jakarta.persistence.PrePersist;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Hibernate 通过 Spring 注入策略；只在创建时填期限，读取和重启均不重算。 */
@Component
public class RetentionEntityListener {
    @Autowired(required = false) private RetentionPolicy policy = new RetentionPolicy();

    @PrePersist
    public void initialize(Object entity) {
        long now = System.currentTimeMillis();
        if (entity instanceof ChatMessage e && e.getRetentionExpiresAt() == null)
            e.setRetentionExpiresAt(Math.max(now, e.getCreatedAt() != null ? e.getCreatedAt() : now) + policy.days(policy.getWorldChatDays()));
        if (entity instanceof GuildChatMessage e && e.getRetentionExpiresAt() == null)
            e.setRetentionExpiresAt(Math.max(now, e.getCreatedAt() != null ? e.getCreatedAt() : now) + policy.days(policy.getGuildChatDays()));
        if (entity instanceof PrivateChatMessage e && e.getRetentionExpiresAt() == null)
            e.setRetentionExpiresAt(Math.max(now, e.getCreatedAt() != null ? e.getCreatedAt() : now) + policy.days(policy.getPrivateChatDays()));
        if (entity instanceof ScoutReport e) {
            if (e.getPersistedRealAt() == null) e.setPersistedRealAt(now);
            if (e.getDetailExpiresAt() == null) e.setDetailExpiresAt(e.getPersistedRealAt() + policy.days(policy.getReportDetailDays()));
            if (e.getSummaryExpiresAt() == null) e.setSummaryExpiresAt(e.getDetailExpiresAt() + policy.days(policy.getReportSummaryDays()));
        }
        if (entity instanceof PvpPracticeReport e) {
            if (e.getPersistedRealAt() <= 0) e.setPersistedRealAt(now);
            if (e.getRetentionExpiresAt() <= 0) e.setRetentionExpiresAt(e.getPersistedRealAt() + policy.days(policy.getPvpDays()));
        }
        if (entity instanceof Mail e) e.initializeRetention(policy, now);
        if (entity instanceof PvpRequest e) {
            if (e.getPersistedRealAt() <= 0) e.setPersistedRealAt(now);
            if (e.getRetentionExpiresAt() <= 0) e.setRetentionExpiresAt(e.getPersistedRealAt() + policy.days(policy.getPvpDays()));
        }
        if (entity instanceof PvpMatchTicket e) {
            if (e.getPersistedRealAt() <= 0) e.setPersistedRealAt(now);
            if (e.getRetentionExpiresAt() <= 0) e.setRetentionExpiresAt(e.getPersistedRealAt() + policy.days(policy.getPvpDays()));
        }
        if (entity instanceof PvpPracticeSession e && e.getHistoryRetentionMs() <= 0) {
            e.setHistoryRetentionMs(policy.days(policy.getPvpDays()));
            if (e.getSettledRealAt() != null) e.setSettledRealAt(e.getSettledRealAt());
        }
    }
}
