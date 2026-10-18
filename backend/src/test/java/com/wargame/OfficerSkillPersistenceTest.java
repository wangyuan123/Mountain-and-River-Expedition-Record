package com.wargame;

import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.BattleSession;
import com.wargame.model.entity.Bandit;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.util.JsonUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实战术会话保存技能起点，历史会话从战报恢复后继续同一个周期。 */
class OfficerSkillPersistenceTest extends BaseServiceTest {
    @Autowired private BattleSessionRepository sessions;
    @PersistenceContext private EntityManager entityManager;

    @Test
    void newSessionKeepsFourSafeRoundsAndEngagesOnFifthAfterReload() {
        var player = createTestPlayer("opening-distance", 30);
        var target = new Bandit();
        target.setWorldId(createTestWorld().getId());
        target.setName("开局距离测试营地");
        target.setLevel(1);
        target.setX(20);
        target.setY(20);
        target.setArmy(JsonUtil.toJson(Map.of("infantry", 100)));
        target.setDefeated(false);
        target = banditRepository.save(target);
        createArmyUnit(player.getId(), "infantry", 100);
        var march = marchService.createDispatch(player.getId(), new DispatchRequest(
                "bandit", target.getId(), "conquer", Map.of("infantry", 100), null, null));
        marchService.processMarches(player.getId(), march.getArriveAt());
        BattleSession session = sessions.findByMarchId(march.getId()).orElseThrow();
        assertEquals(1160, session.getInitialDistance());
        Long sessionId = session.getId();

        for (int round = 1; round <= 5; round++) {
            long deadline = session.getRoundDeadlineAt();
            sessions.flush();
            entityManager.clear();
            marchService.processTimedOutTacticalBattle(sessionId, deadline);
            sessions.flush();
            entityManager.clear();
            session = sessions.findById(sessionId).orElseThrow();
            assertEquals(round, session.getRoundNo());
            assertEquals(round == 5 ? 5 : 0, session.getFirstCombatRound(), session.getBattleLog());
            if (round <= 4) {
                assertEquals(Map.of("infantry", 100), JsonUtil.parseIntMap(session.getAttackerArmy()));
                assertEquals(Map.of("infantry", 100), JsonUtil.parseIntMap(session.getDefenderArmy()));
            }
        }
    }

    @Test
    void reloadAndLegacyRecoveryKeepRoundFourAsCycleAnchor() {
        var player = createTestPlayer("skill-cycle", 30);
        var world = createTestWorld();
        var target = new Bandit();
        target.setWorldId(world.getId());
        target.setName("技能周期测试营地");
        target.setLevel(1);
        target.setX(20);
        target.setY(20);
        target.setArmy(JsonUtil.toJson(Map.of("infantry", 100)));
        target.setDefeated(false);
        target = banditRepository.save(target);
        createArmyUnit(player.getId(), "infantry", 100);
        var march = marchService.createDispatch(player.getId(), new DispatchRequest(
                "bandit", target.getId(), "conquer", Map.of("infantry", 100), null, null));
        marchService.processMarches(player.getId(), march.getArriveAt());
        BattleSession session = sessions.findByMarchId(march.getId()).orElseThrow();
        assertEquals(0, session.getFirstCombatRound());
        session.setRoundNo(3);
        session.setInitialDistance(400);
        session.setAttackerPositions(JsonUtil.toJson(Map.of("infantry", 150)));
        session.setDefenderPositions(JsonUtil.toJson(Map.of("infantry", 250)));
        session.setAttackerSkills(JsonUtil.toJson(Map.of("frenzy", 5)));
        session.setDefenderSkills(JsonUtil.toJson(Map.of("frenzy", 5)));
        session.setAttackerTech(JsonUtil.toJson(Map.of("cmd_hp", 100)));
        session.setDefenderTech(JsonUtil.toJson(Map.of("cmd_hp", 100)));
        long now = System.currentTimeMillis();
        session.setRoundDeadlineAt(now);
        sessions.saveAndFlush(session);
        Long sessionId = session.getId();

        for (int round = 4; round <= 7; round++) {
            entityManager.clear();
            marchService.processTimedOutTacticalBattle(sessionId, now);
            entityManager.flush();
            entityManager.clear();
            session = sessions.findById(sessionId).orElseThrow();
            assertEquals(round, session.getRoundNo());
            assertEquals(4, session.getFirstCombatRound());
            String currentLog = session.getBattleLog().substring(session.getBattleLog().lastIndexOf("-- 第"));
            assertEquals(round == 4 || round == 7, currentLog.contains("全军冲锋"), currentLog);
            if (round == 4) {
                // 模拟升级前没有新增字段的已交战会话；第 5 回合必须恢复 4，不能重新触发。
                session.setFirstCombatRound(null);
                sessions.saveAndFlush(session);
            }
            now += 15_000L;
        }
    }
}
