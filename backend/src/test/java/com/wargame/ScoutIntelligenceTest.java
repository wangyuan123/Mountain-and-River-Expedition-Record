package com.wargame;

import com.wargame.controller.GameController;
import com.wargame.model.UserPrincipal;
import com.wargame.model.entity.ScoutReport;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 出征情报只读取本人成功快照，独立于战报列表分页、详情裁剪与已读状态。 */
class ScoutIntelligenceTest extends BaseServiceTest {
    @Autowired GameController controller;

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    private Long login() {
        Long id = createTestPlayer().getId();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(id, "scout-intelligence", "", false), null));
        return id;
    }

    private ScoutReport report(Long playerId, int x, boolean success, Map<String, Integer> army) {
        ScoutReport report = new ScoutReport();
        report.setPlayerId(playerId); report.setType("scout"); report.setTargetX(x); report.setTargetY(103);
        report.setCreatedAt(100L); report.setReadAt(0L);
        report.setData(JsonUtil.toJson(Map.of("x", x, "y", 103, "showCityInfo", success, "army", army)));
        return scoutReportRepository.saveAndFlush(report);
    }

    @Test void findsSuccessfulSnapshotBeyondFailedAttemptsAndIgnoresOtherPlayersAndTargets() {
        Long player = login();
        ScoutReport expected = report(player, 44, true, Map.of("infantry", 10));
        // 只有摘要仍在保留期的历史报告也可提供当时的情报。
        String summary = expected.getSummaryJson(), card = expected.getListSummaryJson();
        expected.setData(null); expected.setDetailExpiresAt(1L);
        expected.setSummaryJson(summary); expected.setListSummaryJson(card);
        scoutReportRepository.saveAndFlush(expected);
        for (int i = 0; i < 22; i++) report(player, 44, false, Map.of("infantry", 900));
        report(player, 45, true, Map.of("infantry", 800));
        report(createTestPlayer("other-scout", 10).getId(), 44, true, Map.of("infantry", 700));
        var result = controller.getScoutIntelligence(44, 103).getBody();
        assertEquals(true, result.get("found"));
        Map<?, ?> snapshot = (Map<?, ?>) result.get("report");
        assertEquals(expected.getId(), snapshot.get("id"));
        assertEquals(Map.of("infantry", 10), ((Map<?, ?>) snapshot.get("data")).get("army"));
        assertEquals(0L, scoutReportRepository.findById(expected.getId()).orElseThrow().getReadAt());
    }

    @Test void newestSuccessWinsAndEmptySnapshotIsDifferentFromUnknownOrExpired() {
        Long player = login();
        report(player, 44, true, Map.of("infantry", 10));
        ScoutReport empty = report(player, 44, true, Map.of());
        var result = controller.getScoutIntelligence(44, 103).getBody();
        Map<?, ?> snapshot = (Map<?, ?>) result.get("report");
        assertEquals(empty.getId(), snapshot.get("id"));
        assertEquals(Map.of(), ((Map<?, ?>) snapshot.get("data")).get("army"));
        assertEquals(false, controller.getScoutIntelligence(46, 103).getBody().get("found"));
        ScoutReport expired = report(player, 47, true, Map.of("infantry", 30));
        expired.setSummaryExpiresAt(1L); scoutReportRepository.saveAndFlush(expired);
        assertEquals(false, controller.getScoutIntelligence(47, 103).getBody().get("found"));
        expired.setStarredAt(System.currentTimeMillis()); scoutReportRepository.saveAndFlush(expired);
        assertEquals(true, controller.getScoutIntelligence(47, 103).getBody().get("found"));
        assertThrows(IllegalArgumentException.class, () -> controller.getScoutIntelligence(-1, 103));
        assertThrows(IllegalArgumentException.class, () -> controller.getScoutIntelligence(44, 800));
    }
}
