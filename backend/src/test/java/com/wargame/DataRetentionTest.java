package com.wargame;

import com.wargame.config.RetentionPolicy;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.model.UserPrincipal;
import com.wargame.controller.GameController;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 验证真实期限、观察门禁、附件权利与分批回收的业务边界。 */
@ExtendWith(OutputCaptureExtension.class)
class DataRetentionTest extends BaseServiceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired RetentionBatchService batches;
    @Autowired RetentionPolicy policy;
    @Autowired MailService mails;
    @Autowired MailRepository mailRepository;
    @Autowired MailGiftActivityService gifts;
    @Autowired MailGiftActivityRepository activities;
    @Autowired MailGiftDeliveryRepository deliveries;
    @Autowired PvpPracticeSessionRepository sessions;
    @Autowired PvpPracticeReportRepository pvpReports;
    @Autowired GameController gameController;
    @Autowired ForumService forum;
    @Autowired ForumBoardRepository boards;
    @Autowired ForumTopicRepository topics;
    @Autowired RetentionMetricsService metrics;
    private static final long DAY = RetentionPolicy.DAY;
    private static final String OWNER = "retention-test";
    private static final String BATTLE = "{\"win\":true,\"perspective\":\"defender\",\"attackerWin\":false,\"initialAttacker\":{\"infantry\":2},\"survivorAttacker\":{},\"initialDefender\":{\"armored_car\":500},\"survivorDefender\":{\"armored_car\":499},\"roundLogs\":[\"旧日志\"]}";

    @BeforeEach void control() {
        jdbc.update("DELETE FROM data_retention_control");
        jdbc.update("INSERT INTO data_retention_control(control_id,observation_started_at,lease_owner,lease_until,last_run_at,cleanup_enabled,next_category,backfill_cursor,detail_cursor) VALUES(1,?,?,?,0,TRUE,0,0,0)", System.currentTimeMillis() - 8 * DAY, OWNER, System.currentTimeMillis() + 60000);
    }

    private RetentionBatchService.Result run(String category) {
        em.flush();
        var result = batches.execute(OWNER, category, System.nanoTime() + 5_000_000_000L);
        em.clear();
        return result;
    }

    private void expiredChat(int count) {
        for (int i = 0; i < count; i++) jdbc.update("INSERT INTO chat_messages(player_id,username,content,created_at,retention_expires_at) VALUES(1,'test','old',?,?)", System.currentTimeMillis() - 8 * DAY, System.currentTimeMillis() - 1);
    }

    private ScoutReport report(Long player, String data) {
        ScoutReport report = new ScoutReport();
        report.setPlayerId(player); report.setType("battle"); report.setCreatedAt(1L);
        report.setData(data); report.setDetailExpiresAt(System.currentTimeMillis() - 1);
        report.setSummaryExpiresAt(System.currentTimeMillis() + DAY);
        return scoutReportRepository.saveAndFlush(report);
    }

    @Test void observationAndDisabledSwitchPreventPhysicalChanges() {
        expiredChat(2);
        jdbc.update("UPDATE data_retention_control SET observation_started_at=?", System.currentTimeMillis() - 6 * DAY);
        assertFalse(run("chat").cleanup());
        jdbc.update("UPDATE data_retention_control SET observation_started_at=?,cleanup_enabled=FALSE", System.currentTimeMillis() - 8 * DAY);
        assertFalse(run("chat").cleanup());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class));
    }

    @Test void cleanupIsBoundedAndLeaseAndBudgetAreEnforced() {
        expiredChat(205);
        assertEquals(200, run("chat").changed());
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class));
        assertEquals(0, batches.execute("wrong-owner", "chat", Long.MAX_VALUE).changed());
        assertEquals(0, batches.execute(OWNER, "chat", System.nanoTime() - 1).changed());
        jdbc.update("UPDATE data_retention_control SET lease_until=0");
        assertEquals(0, run("chat").changed());
    }

    @Test void trimPreservesHistoricalForcesAndProtectsFavoritesAndInvalidSnapshots() {
        Long player = createTestPlayer().getId();
        var normal = report(player, BATTLE);
        var starred = report(player, BATTLE); starred.setStarredAt(System.currentTimeMillis());
        var invalid = report(player, "{\"roundLogs\":[\"仅有日志，没有兵力快照\"]}");
        assertEquals(1, run("details").changed());
        var trimmed = scoutReportRepository.findById(normal.getId()).orElseThrow();
        assertNull(trimmed.getData());
        assertTrue(trimmed.getSummaryJson().contains("500"));
        assertFalse(trimmed.getSummaryJson().contains("roundLogs"));
        assertNotNull(scoutReportRepository.findById(starred.getId()).orElseThrow().getData());
        assertNotNull(scoutReportRepository.findById(invalid.getId()).orElseThrow().getData());
        assertTrue(ReportSummary.cardJson(BATTLE).contains("attackerWin"));
    }

    @Test void observationDoesNotTrimAndSummaryExpiryDeletesItsShares() {
        Long player = createTestPlayer().getId();
        var r = report(player, BATTLE);
        jdbc.update("UPDATE data_retention_control SET cleanup_enabled=FALSE");
        assertEquals(0, run("details").changed());
        assertNotNull(scoutReportRepository.findById(r.getId()).orElseThrow().getData());
        jdbc.update("UPDATE data_retention_control SET cleanup_enabled=TRUE");
        // 观察期内的详情游标可能已越过该记录；下一次空批次回绕后仍须处理。
        assertEquals(0, run("details").changed());
        assertEquals(1, run("details").changed());
        jdbc.update("UPDATE scout_reports SET summary_expires_at=? WHERE id=?", System.currentTimeMillis() - 1, r.getId());
        jdbc.update("INSERT INTO shared_reports(token,owner_id,report_id,expires_at) VALUES('share',?,?,?)", player, r.getId(), System.currentTimeMillis());
        run("reports");
        run("reports");
        assertFalse(scoutReportRepository.existsById(r.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM shared_reports", Integer.class));
    }

    @Test void favoriteRequiresLiveDetailsAndKeepsThemWhenCleanupRuns() {
        Long player = createTestPlayer().getId();
        var expired = report(player, BATTLE);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(player, "retention", "", false), null));
        try {
            assertThrows(IllegalArgumentException.class, () -> gameController.starReport(expired.getId()));
            jdbc.update("UPDATE scout_reports SET detail_expires_at=? WHERE id=?", System.currentTimeMillis() + DAY, expired.getId());
            em.clear();
            gameController.starReport(expired.getId());
            em.flush();
            jdbc.update("UPDATE scout_reports SET detail_expires_at=? WHERE id=?", System.currentTimeMillis() - 1, expired.getId());
            em.clear();
            assertEquals(0, run("details").changed());
            assertNotNull(scoutReportRepository.findById(expired.getId()).orElseThrow().getData());
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void oldUnknownAssetsStayProtectedAndClaimStartsFullThirtyDays() {
        Long player = createTestPlayer().getId();
        var mail = mails.sendSystem(player, "系统", "reward", "旧资产", "", List.of(Map.of("type", "gold", "qty", 10)));
        mail.setCreatedAt(System.currentTimeMillis() - 1000 * DAY);
        mail.setIsRead(true); mailRepository.saveAndFlush(mail);
        assertNull(mail.getRetentionExpiresAt());
        assertEquals(0, run("mails").changed());
        assertTrue((Boolean) mails.claimAttach(player, mail.getId()).get("success"));
        var claimed = mailRepository.findById(mail.getId()).orElseThrow();
        assertTrue(claimed.getRetentionExpiresAt() >= System.currentTimeMillis() + 30 * DAY - 1000);
        assertThrows(IllegalArgumentException.class, () -> mails.claimAttach(player, mail.getId()));
    }

    @Test void readMailUsesThirtyDaySnapshotAndUnreadUsesSixtyDays() {
        Long player = createTestPlayer().getId();
        var mail = mails.sendSystem(player, "系统", "system", "通知", "正文", List.of());
        assertEquals(60 * DAY, mail.getRetentionExpiresAt() - mail.getPersistedRealAt());
        int old = policy.getProcessedMailDays();
        try {
            policy.setProcessedMailDays(1);
            mails.markRead(player, mail.getId());
            assertEquals(30 * DAY, mail.getRetentionExpiresAt() - mail.getProcessedRealAt());
        } finally { policy.setProcessedMailDays(old); }
        assertTrue(mails.listPage(player, "inbox", null, 20).get("mails").toString().contains("bodyPreview"));
        assertFalse(mails.listPage(player, "inbox", null, 20).get("mails").toString().contains("body=正文"));
    }

    @Test void expiredGiftCannotCreditAndNoticeExpiresAfterSevenDays() {
        Long player = createTestPlayer().getId();
        var mail = mails.sendSystem(player, "活动", "reward", "赠礼", "", List.of(Map.of("type", "gold", "qty", 10)), null, "TIMED_GIFT", "LOGIN_ACTIVITY", "event", System.currentTimeMillis() + DAY);
        long boundary = System.currentTimeMillis();
        mail.setAttachmentExpiresAt(boundary); mail.setCleanupAfter(boundary + 7 * DAY); mail.setRetentionExpiresAt(boundary + 7 * DAY);
        mailRepository.saveAndFlush(mail);
        int gold = getResources(player).getGold();
        assertEquals(false, mails.claimAttach(player, mail.getId()).get("success"));
        assertEquals(gold, getResources(player).getGold());
        assertFalse(mail.getIsClaimed());
        assertTrue((Boolean) mails.detail(player, mail.getId()).get("attachmentExpired"));
        assertEquals(1, mails.unreadCount(player));
        mail.setCleanupAfter(boundary - 1); mail.setRetentionExpiresAt(boundary - 1); mailRepository.saveAndFlush(mail);
        assertEquals(0, mails.unreadCount(player));
        assertThrows(IllegalArgumentException.class, () -> mails.detail(player, mail.getId()));
        assertEquals(1, run("mails").changed());
    }

    @Test void activityLoginIsIdempotentAfterMailDeletionAndCloseOrEndStopsIssuance() {
        Long player = createTestPlayer().getId();
        long now = System.currentTimeMillis();
        var activity = gifts.create("holiday", now - DAY, now + DAY, false, "节日赠礼", "", List.of(Map.of("type", "gold", "qty", 10)));
        assertEquals(1, gifts.issueOnLogin(player));
        assertEquals(0, gifts.issueOnLogin(player));
        var delivery = deliveries.findAll().get(0);
        mails.delete(player, delivery.getMailId());
        assertEquals(0, gifts.issueOnLogin(player));
        Long other = createTestPlayer("other", 30).getId();
        gifts.close(activity.getId());
        assertEquals(0, gifts.issueOnLogin(other));
        activity.setClosed(false); activity.setEndsAt(now - 1); activities.saveAndFlush(activity);
        assertEquals(0, gifts.issueOnLogin(other));
        assertEquals(1, deliveries.count());
    }

    @Test void activeBusinessProtectsMailAndTerminationStartsThirtyDaysInObservation() {
        Long player = createTestPlayer().getId();
        CitySurrenderOffer offer = new CitySurrenderOffer();
        offer.setWorldId(1L); offer.setInitiatorId(player); offer.setDefenderId(player); offer.setCityId(1L);
        offer.setCityName("城"); offer.setCityX(0); offer.setCityY(0); offer.setStatus("PENDING");
        offer.setSentAt(1L); offer.setResponseExpiresAt(Long.MAX_VALUE);
        em.persist(offer); em.flush();
        var mail = mails.sendSystem(player, "系统", "surrender", "劝降", "", List.of(), offer.getId());
        mails.markRead(player, mail.getId());
        assertNull(mail.getRetentionExpiresAt());
        offer.setStatus("REJECTED"); em.flush();
        jdbc.update("UPDATE data_retention_control SET cleanup_enabled=FALSE");
        long ended = System.currentTimeMillis();
        mails.businessEnded(offer.getId(), ended);
        var finished = mailRepository.findById(mail.getId()).orElseThrow();
        assertEquals(ended + 30 * DAY, finished.getRetentionExpiresAt());
    }

    @Test void pvpSessionNeedsBothReportsGoneAndNoActiveReference() {
        Long a = createTestPlayer("a", 30).getId(), b = createTestPlayer("b", 30).getId();
        PvpPracticeSession session = new PvpPracticeSession(); session.setId("session");
        session.setPlayerA(a); session.setPlayerB(b); session.setStatus("FINISHED");
        session.setSettledAt(1L); session.setSettledRealAt(System.currentTimeMillis() - 31 * DAY);
        sessions.saveAndFlush(session);
        PvpPracticeReport report = new PvpPracticeReport(); report.setId("b-report"); report.setSessionId("session");
        report.setPlayerId(b); report.setData("{}"); pvpReports.saveAndFlush(report);
        assertEquals(0, run("pvpSessions").changed());
        pvpReports.deleteById(report.getId()); pvpReports.flush();
        jdbc.update("INSERT INTO pvp_player_states(player_id,rating,games,wins,losses,draws,confirmation_exits,confirmation_window,cooldown_until,preset_version,sequence_no,version,active_session_id,last_session_id) VALUES(?,1000,0,0,0,0,0,0,0,0,0,0,'session','session')", a);
        assertEquals(0, run("pvpSessions").changed());
        jdbc.update("UPDATE pvp_player_states SET active_session_id=NULL");
        assertEquals(1, run("pvpSessions").changed());
        assertNull(jdbc.queryForObject("SELECT last_session_id FROM pvp_player_states WHERE player_id=?", String.class, a));
    }

    @Test void forumShareHonorsExpiryAndRedactsDeletedParticipants() {
        Player owner = createTestPlayer("forum-retention-owner", 30);
        owner.setPrestige(200);
        Player deleted = createTestPlayer("forum-deleted-enemy", 30);
        deleted.setAccountStatus("DELETED");
        var report = report(owner.getId(), BATTLE.replace("\"win\":true", "\"attackerName\":\"forum-deleted-enemy\",\"win\":true"));
        report.setDetailExpiresAt(System.currentTimeMillis() + DAY);
        em.flush();
        var board = boards.findByBoardKey("battle_hall").orElseThrow();
        var topic = forum.createTopic(owner.getId(), new com.wargame.model.dto.ForumDtos.CreateTopicRequest(
                board.getId(), "保留策略战报分享", "历史战果", "BATTLE", 0, report.getId()));
        var snapshot = (Map<?, ?>) forum.getTopicDetail(topic.id()).battleReport();
        assertEquals("已注销玩家", snapshot.get("attackerName"));
        assertEquals(report.getDetailExpiresAt(), snapshot.get("expiresAt"));
        assertTrue(scoutReportRepository.findById(report.getId()).orElseThrow().getData().contains("forum-deleted-enemy"));
        var saved = topics.findById(topic.id()).orElseThrow();
        saved.setBattleReportExpiresAt(System.currentTimeMillis() - 1);
        var expired = (Map<?, ?>) forum.getTopicDetail(topic.id()).battleReport();
        assertEquals(true, expired.get("expired"));
        assertFalse(expired.containsKey("initialDefender"));
    }

    @Test void metricsCollectsBacklogWithoutChangingData(CapturedOutput output) {
        expiredChat(2);
        Long player = createTestPlayer("metrics-owner", 30).getId();
        report(player, BATTLE);
        em.flush();
        metrics.collect();
        assertFalse(output.getAll().contains("Retention metrics unavailable"));
        assertTrue(output.getAll().contains("table=chat_messages rows=2"));
        assertTrue(output.getAll().contains("category=scout_reports.details oldestExpiredAt="));
        assertTrue(output.getAll().contains("table=pvp_round_results"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM scout_reports WHERE data IS NOT NULL", Integer.class));
    }
}
