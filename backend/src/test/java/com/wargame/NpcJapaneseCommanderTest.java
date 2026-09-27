package com.wargame;

import com.wargame.model.constants.JapaneseOfficers;
import com.wargame.model.constants.WorldConfig;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.CityScope;
import com.wargame.service.MarchService;
import com.wargame.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class NpcJapaneseCommanderTest {

    @Autowired private MarchService marchService;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private PlayerCityRepository playerCityRepository;
    @Autowired private BanditRepository banditRepository;
    @Autowired private CityScope cityScope;
    @Autowired private ResourcesRepository resourcesRepository;
    @Autowired private ScoutReportRepository scoutReportRepository;
    @Autowired private MarchRepository marchRepository;

    private Long playerId;

    @BeforeEach
    void setUp() {
        Player player = new Player();
        player.setUsername("testCommanderPlayer");
        player.setPasswordHash("hash");
        player.setCityPosX(100);
        player.setCityPosY(100);
        player = playerRepository.save(player);
        playerId = player.getId();

        PlayerCity city = new PlayerCity();
        city.setOwnerId(playerId);
        city.setWorldId(1L);
        city.setCitySlot(0);
        city.setName("指挥官测试城");
        city.setX(100);
        city.setY(100);
        playerCityRepository.save(city);

        Resources res = new Resources();
        res.setPlayerId(playerId);
        res.setCitySlot(0);
        res.setFood(100000);
        res.setSteel(100000);
        res.setOil(100000);
        res.setRare(100000);
        res.setGold(100000);
        res.setDiamond(0);
        resourcesRepository.save(res);
    }

    private void settleWithDefaultTactics(March march, long roundTime) {
        marchService.processMarches(playerId, roundTime);
        for (int round = 0; round < 30 && marchRepository.findById(march.getId()).isPresent(); round++) {
            March activeMarch = marchRepository.findById(march.getId()).orElseThrow();
            if (activeMarch.getBattleId() == null) break;
            roundTime += 15_000L;
            marchService.processTimedOutTacticalBattle(activeMarch.getBattleId(), roundTime);
        }
    }

    @Test
    void testJapaneseOfficersDefinitionsAndAttributes() {
        // 等级映射与历史将领名称校验
        assertEquals("细谷资彦", JapaneseOfficers.getCommanderForLevel(1));
        assertEquals("田中新一", JapaneseOfficers.getCommanderForLevel(10));
        assertEquals("木村兵太郎", JapaneseOfficers.getCommanderForLevel(28));
        assertEquals("牟田口廉也", JapaneseOfficers.getCommanderForLevel(29));
        assertEquals("寺内寿一", JapaneseOfficers.getCommanderForLevel(30));

        // 低等级将领构建
        Officer lowOfficer = JapaneseOfficers.buildOfficer(null, 1);
        assertEquals("细谷资彦", lowOfficer.getName());
        assertEquals(1, lowOfficer.getStar());
        assertEquals(3, lowOfficer.getLevel());
        assertEquals(51, lowOfficer.getMilitary());
        assertEquals(51, lowOfficer.getDefense());

        // 高等级将领构建（含技能）
        Officer maxOfficer = JapaneseOfficers.buildOfficer("牟田口廉也", 30);
        assertEquals("牟田口廉也", maxOfficer.getName());
        assertEquals(5, maxOfficer.getStar());
        assertEquals(90, maxOfficer.getLevel());
        assertEquals(225, maxOfficer.getMilitary());
        assertEquals(225, maxOfficer.getDefense());
        assertTrue(maxOfficer.getSkills().contains("counter"));
        assertTrue(maxOfficer.getSkills().contains("bulwark"));
        assertTrue(maxOfficer.getSkills().contains("frenzy"));
        assertTrue(maxOfficer.getSkills().contains("pierce"));
    }

    @Test
    void testBanditCombatAssignsJapaneseCommanderInReport() {
        Bandit bandit = new Bandit();
        bandit.setWorldId(1L);
        bandit.setName("日寇据点 Lv.10");
        bandit.setLevel(10);
        bandit.setX(101);
        bandit.setY(100);
        bandit.setArmy(JsonUtil.toJson(Map.of("infantry", 10)));
        bandit.setCommanderName("田中新一");
        bandit.setDefeated(false);
        bandit = banditRepository.save(bandit);

        long now = System.currentTimeMillis();
        March march = new March();
        march.setPlayerId(playerId);
        march.setTargetKind("bandit");
        march.setTargetId(String.valueOf(bandit.getId()));
        march.setTargetName(bandit.getName());
        march.setTargetX(bandit.getX());
        march.setTargetY(bandit.getY());
        march.setFromX(100);
        march.setFromY(100);
        march.setAction("conquer");
        march.setArmy(JsonUtil.toJson(Map.of("htank", 500)));
        march.setCarryRes("{}");
        march.setReturning(false);
        march.setBattleMode("auto");
        march.setArriveAt(now - 1000);
        march = marchRepository.save(march);

        settleWithDefaultTactics(march, now);

        // 验证生成战报包含防守方日军将领
        var reports = scoutReportRepository.findByPlayerIdOrderByCreatedAtDesc(playerId);
        assertFalse(reports.isEmpty());
        ScoutReport report = reports.get(0);
        assertEquals("battle", report.getType());
        Map<String, Object> data = JsonUtil.parseObjMap(report.getData());
        assertNotNull(data);
        @SuppressWarnings("unchecked")
        Map<String, Object> commanders = (Map<String, Object>) data.get("commanders");
        assertNotNull(commanders);
        @SuppressWarnings("unchecked")
        Map<String, Object> defender = (Map<String, Object>) commanders.get("defender");
        assertNotNull(defender, "战报中应包含敌军日军指挥官");
        assertEquals("田中新一", defender.get("name"));
        assertTrue(((Number) defender.get("military")).intValue() > 0);
    }

    @Test
    void testDefeatingMaxLevelBanditDropsDiamondsAndCreditsToWallet() {
        Bandit maxBandit = new Bandit();
        maxBandit.setWorldId(1L);
        maxBandit.setName("日寇总督府 Lv.30");
        maxBandit.setLevel(30);
        maxBandit.setX(102);
        maxBandit.setY(100);
        maxBandit.setArmy(JsonUtil.toJson(Map.of("infantry", 10)));
        maxBandit.setCommanderName("寺内寿一");
        maxBandit.setDefeated(false);
        maxBandit = banditRepository.save(maxBandit);

        long now = System.currentTimeMillis();
        March march = new March();
        march.setPlayerId(playerId);
        march.setTargetKind("bandit");
        march.setTargetId(String.valueOf(maxBandit.getId()));
        march.setTargetName(maxBandit.getName());
        march.setTargetX(maxBandit.getX());
        march.setTargetY(maxBandit.getY());
        march.setFromX(100);
        march.setFromY(100);
        march.setAction("conquer");
        march.setArmy(JsonUtil.toJson(Map.of("htank", 1000)));
        march.setCarryRes("{}");
        march.setReturning(false);
        march.setBattleMode("auto");
        march.setArriveAt(now - 1000);
        march = marchRepository.save(march);

        // 攻击结算
        settleWithDefaultTactics(march, now);

        // 验证行军携带战利品中包含钻石
        March returningMarch = marchRepository.findById(march.getId()).orElseThrow();
        Map<String, Integer> carryRes = JsonUtil.parseIntMap(returningMarch.getCarryRes());
        assertTrue(carryRes.containsKey("diamond"), "击败最高级据点应缴获钻石");
        assertEquals(20, carryRes.get("diamond"));

        // 验证返程抵达主城入库
        returningMarch.setArriveAt(now);
        marchRepository.save(returningMarch);
        marchService.processMarches(playerId, now + 1000);

        // 验证玩家共享钱包的钻石增加 20
        Resources wallet = cityScope.wallet(playerId);
        assertEquals(20, wallet.getDiamond(), "返程后钻石应存入玩家钱包");
    }
}
