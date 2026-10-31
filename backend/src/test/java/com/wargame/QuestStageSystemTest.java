package com.wargame;

import com.wargame.model.constants.ItemDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.CityScope;
import com.wargame.service.DepotService;
import com.wargame.service.quest.QuestCatalog;
import com.wargame.service.quest.QuestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class QuestStageSystemTest {

    @Autowired
    private QuestService questService;

    @Autowired
    private DepotService depotService;

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private ResourcesRepository resourcesRepository;

    @Autowired
    private PlayerItemRepository playerItemRepository;

    @Autowired
    private PlayerQuestRepository playerQuestRepository;

    @Autowired
    private PlayerQuestStageRepository playerQuestStageRepository;

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private ArmyUnitRepository armyUnitRepository;

    @Autowired
    private CityScope cityScope;

    @Test
    @DisplayName("验证8个阶段定义完整性，涵盖16大需求及阶段专属大礼包")
    void testQuestCatalogIntegrity() {
        assertEquals(8, QuestCatalog.CHAPTERS.size(), "主线任务必须包含8个阶段");

        Set<String> requiredEvents = Set.of(
                "BUILD_UPGRADE_DONE", "BUILD_ALL_MAX",
                "TECH_LEVEL", "TECH_LEVEL_COUNT", "TECH_MAX_COUNT", "TECH_ALL_MAX",
                "ARMY_TOTAL", "ARMY_NAVY_TOTAL", "ARMY_AIR_TOTAL", "ARMY_ARMOR_TOTAL",
                "PRESTIGE_VALUE", "MILITARY_RANK_TIER",
                "SCOUT_WILD", "WILD_PLUNDER", "WILD_CONQUER",
                "SCOUT_PLAYER", "WAR_DECLARE", "PLAYER_PLUNDER", "PLAYER_CONQUER",
                "CITY_COUNT", "OFFICER_RENAME", "DISPLAY_NAME_CHANGE", "CITY_NAME_CHANGE",
                "BATTLE_COMMAND", "BANDIT_DEFEAT", "CAPTIVE_OFFICER", "FORUM_POST", "HISTORICAL_OFFICER"
        );

        Set<String> presentEvents = new HashSet<>();
        for (int i = 0; i < QuestCatalog.CHAPTERS.size(); i++) {
            QuestCatalog.Chapter ch = QuestCatalog.CHAPTERS.get(i);
            assertEquals("stage" + (i + 1), ch.id());
            assertEquals("stage_gift_" + (i + 1), ch.giftKey());
            assertTrue(ItemDef.ITEMS.containsKey(ch.giftKey()), "ItemDef必须包含礼包: " + ch.giftKey());
            assertTrue(DepotService.STAGE_GIFTS.containsKey(ch.giftKey()), "DepotService必须包含礼包: " + ch.giftKey());

            for (QuestCatalog.Quest q : ch.quests()) {
                presentEvents.add(q.eventType());
            }
        }

        for (String reqEv : requiredEvents) {
            assertTrue(presentEvents.contains(reqEv), "任务系统必须覆盖事件需求: " + reqEv);
        }
    }

    @Test
    @DisplayName("测试在仓库开启阶段豪华大礼包：各类资源、黄金、钻石及赠品正确入账")
    void testStageGiftUseInDepot() {
        Player player = new Player();
        player.setUsername("gift_test_user_" + System.currentTimeMillis());
        player.setPasswordHash("hash");
        player = playerRepository.save(player);
        Long pid = player.getId();

        Resources res = new Resources();
        res.setPlayerId(pid);
        res.setCitySlot(0);
        res.setFood(100);
        res.setSteel(100);
        res.setOil(100);
        res.setRare(100);
        res.setGold(100);
        res.setDiamond(10);
        resourcesRepository.save(res);

        // 给玩家放入第1阶段礼包 stage_gift_1
        PlayerItem pi = new PlayerItem();
        pi.setPlayerId(pid);
        pi.setItemKey("stage_gift_1");
        pi.setCount(1);
        pi.setUpdatedAt(System.currentTimeMillis());
        playerItemRepository.save(pi);

        // 使用礼包
        Map<String, Object> result = depotService.useItem(pid, "stage_gift_1", null, null);
        assertTrue((Boolean) result.get("success"), "开启礼包应成功");

        // 验证道具已消耗
        PlayerItem afterItem = playerItemRepository.findByPlayerIdAndItemKey(pid, "stage_gift_1").orElse(null);
        assertEquals(0, afterItem != null ? afterItem.getCount() : 0);

        // 验证资源与钻石已到账
        Resources afterRes = resourcesRepository.findByPlayerIdAndCitySlot(pid, 0).orElseThrow();
        assertEquals(100 + 20000, afterRes.getFood());
        assertEquals(100 + 20000, afterRes.getSteel());
        assertEquals(100 + 20000, afterRes.getOil());
        assertEquals(100 + 5000, afterRes.getRare());
        assertEquals(100 + 2000, afterRes.getGold());
        assertEquals(10 + 50, afterRes.getDiamond(), "第1阶段礼包应发放50钻石");

        // 验证附赠道具到账
        PlayerItem speed10m = playerItemRepository.findByPlayerIdAndItemKey(pid, "speedUp10m").orElse(null);
        assertNotNull(speed10m);
        assertEquals(5, speed10m.getCount());
    }

    @Test
    @DisplayName("测试阶段推进流程：Stage1任务完成 -> 领取Stage1通关大礼包发至仓库 -> Stage2自动解锁")
    void testQuestStageFlow() {
        Player player = new Player();
        player.setUsername("flow_test_user_" + System.currentTimeMillis());
        player.setPasswordHash("hash");
        player = playerRepository.save(player);
        Long pid = player.getId();

        Resources res = new Resources();
        res.setPlayerId(pid);
        res.setCitySlot(0);
        res.setFood(100);
        res.setSteel(100);
        res.setOil(100);
        res.setRare(100);
        res.setGold(100);
        res.setDiamond(0);
        resourcesRepository.save(res);

        // 初始化任务列表
        List<Map<String, Object>> chapters = questService.getPlayerQuests(pid);
        assertEquals(8, chapters.size());
        assertEquals("in_progress", chapters.get(0).get("status"));
        assertEquals("locked", chapters.get(1).get("status"), "未通关第一阶段时第二阶段应处于锁定状态");

        // 建筑等级必须由实际建筑快照证明，不能仅伪造 completed 状态绕过校验。
        for (String type : List.of("house", "farm", "refinery")) {
            Building building = new Building(); building.setPlayerId(pid); building.setCitySlot(0);
            building.setType(type); building.setLevel(2); buildingRepository.save(building);
        }
        // 其他已达成目标保留完成记录，战损等后续状态变化不撤销历史成果。
        QuestCatalog.Chapter ch1 = QuestCatalog.CHAPTERS.get(0);
        for (QuestCatalog.Quest q : ch1.quests()) {
            PlayerQuest pq = playerQuestRepository.findByPlayerIdAndQuestId(pid, q.id()).orElseThrow();
            pq.setStatus("completed");
            pq.setProgress(q.targetValue());
            playerQuestRepository.save(pq);
        }

        // 刷新状态，第一阶段状态应变为 completed (可领大奖)
        chapters = questService.getPlayerQuests(pid);
        assertEquals("completed", chapters.get(0).get("status"));
        assertTrue((Boolean) chapters.get(0).get("canClaimStage"), "第一阶段应可领取阶段大奖");

        // 领取阶段大奖
        Map<String, Object> claimRes = questService.claimStage(pid, "stage1");
        assertTrue((Boolean) claimRes.get("success"), "领取阶段大奖应成功");
        assertEquals("stage_gift_1", claimRes.get("giftKey"));

        // 验证礼包已发放到仓库背包
        PlayerItem giftItem = playerItemRepository.findByPlayerIdAndItemKey(pid, "stage_gift_1").orElse(null);
        assertNotNull(giftItem, "仓库中必须收到阶段大礼包");
        assertEquals(1, giftItem.getCount());

        // 验证第二阶段自动解锁为 in_progress
        chapters = questService.getPlayerQuests(pid);
        assertEquals("claimed", chapters.get(0).get("status"), "第一阶段应为 claimed");
        assertEquals("in_progress", chapters.get(1).get("status"), "第二阶段应已解锁为 in_progress");
    }
}
