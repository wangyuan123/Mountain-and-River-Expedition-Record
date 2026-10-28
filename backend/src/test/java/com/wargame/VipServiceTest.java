package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.GameClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VIP 特许系统（战时特勤统帅部）集成测试")
public class VipServiceTest extends BaseServiceTest {

    @Autowired
    private VipService vipService;

    @Autowired
    private TechService techService;

    @Autowired
    private WoundedService woundedService;

    @Autowired
    private DepotService depotService;

    @Autowired
    private PlayerItemRepository playerItemRepository;

    @Autowired
    private MailRepository mailRepository;

    @Autowired
    private CityScope cityScope;

    @Test
    @DisplayName("VIP 每日军饷按游戏北京时间刷新，不受服务器自然日影响")
    void dailyVipDateUsesGameClockInShanghai() throws Exception {
        Player player = createTestPlayer("vip_test_game_date", 30);
        player.setVipLevel(0);
        player.setLastVipDailyRewardDate(LocalDate.of(2026, 10, 26));
        player.setStoredDailySalariesCount(0);
        playerRepository.saveAndFlush(player);

        // 15:30 UTC 仍是北京时间 10 月 26 日 23:30；服务器若按日本自然日已进入 10 月 27 日。
        try (AutoCloseable ignored = GameClock.use(() -> Instant.parse("2026-10-26T15:30:00Z").toEpochMilli())) {
            assertThrows(IllegalArgumentException.class, () -> vipService.claimDailySalary(player.getId()));
            assertEquals(true, vipService.getVipStatus(player.getId()).get("dailyClaimed"));
        }
    }

    @Test
    @DisplayName("测试累计充值晋升 VIP 等级与表彰委任状邮件")
    void testRechargeProgressionAndPromotionMail() {
        Player player = createTestPlayer("vip_test_recharge", 30);
        Long playerId = player.getId();

        // 初始为 VIP 0
        assertEquals(0, player.getVipLevel());
        assertEquals(0L, player.getTotalRechargeCents());

        // 充值 600 分 (¥6) -> 晋升 VIP 1
        var res1 = vipService.onRechargeSuccess(playerId, 600L);
        assertTrue((Boolean) res1.get("levelUp"));
        assertEquals(1, res1.get("vipLevel"));

        Player refreshed = playerRepository.findById(playerId).orElseThrow();
        assertEquals(1, refreshed.getVipLevel());
        assertEquals(600L, refreshed.getTotalRechargeCents());

        // 再次充值 9200 分 (累计 9800 分，¥98) -> 晋升 VIP 3
        var res2 = vipService.onRechargeSuccess(playerId, 9200L);
        assertTrue((Boolean) res2.get("levelUp"));
        assertEquals(3, res2.get("vipLevel"));

        refreshed = playerRepository.findById(playerId).orElseThrow();
        assertEquals(3, refreshed.getVipLevel());
        // VIP 3 自动赋予青铜战役头像框
        assertEquals("frame-bronze-v1", refreshed.getAvatarFrame());

        // 检查晋升委任状邮件已发放
        List<Mail> mails = mailRepository.findByToPlayerIdOrderByCreatedAtDesc(playerId);
        assertFalse(mails.isEmpty());
        assertTrue(mails.stream().anyMatch(m -> m.getSubject() != null && m.getSubject().contains("晋升委任状")));
    }

    @Test
    @DisplayName("头像框使用公开标识并校验对应 VIP 等级")
    void avatarFrameUsesCanonicalIdsAndVipRequirements() {
        Player player = createTestPlayer("vip_test_avatar_frame", 30);
        String[][] frames = {
                {"frame-bronze-v1", "1"},
                {"frame-silver-v1", "3"},
                {"frame-gold-v1", "5"},
                {"frame-jade-v1", "7"},
                {"frame-supreme-v1", "9"}
        };

        for (String[] frame : frames) {
            int requiredLevel = Integer.parseInt(frame[1]);
            player.setVipLevel(requiredLevel - 1);
            playerRepository.saveAndFlush(player);
            assertThrows(IllegalArgumentException.class,
                    () -> vipService.setAvatarFrame(player.getId(), frame[0]));

            player.setVipLevel(requiredLevel);
            playerRepository.saveAndFlush(player);
            var result = vipService.setAvatarFrame(player.getId(), frame[0]);
            assertEquals(frame[0], result.get("avatarFrame"));
            assertEquals(frame[0], playerRepository.findById(player.getId()).orElseThrow().getAvatarFrame());
        }

        assertThrows(IllegalArgumentException.class,
                () -> vipService.setAvatarFrame(player.getId(), "unknown-frame"));
    }

    @Test
    @DisplayName("测试常备口粮/每日特勤津贴领取与单日防重复")
    void testDailySalaryClaimAndBacklog() {
        Player player = createTestPlayer("vip_test_salary", 30);
        Long playerId = player.getId();

        // VIP 0 领受常备口粮
        var claimRes = vipService.claimDailySalary(playerId);
        assertTrue((Boolean) claimRes.get("success"));

        // 再次领取应提示今日已领取
        assertThrows(IllegalArgumentException.class, () -> vipService.claimDailySalary(playerId));

        // 晋升为 VIP 5（¥328 = 32800 cents）
        vipService.onRechargeSuccess(playerId, 32800L);

        // 模拟离线补发积攒天数
        Player p = playerRepository.findById(playerId).orElseThrow();
        p.setStoredDailySalariesCount(2); // 积攒 2 天
        p.setLastVipDailyRewardDate(LocalDate.now().minusDays(3));
        playerRepository.save(p);

        var backlogRes = vipService.claimDailySalary(playerId);
        assertTrue((Boolean) backlogRes.get("success"));
        assertEquals(0, backlogRes.get("storedDaysRemaining"));
    }

    @Test
    @DisplayName("测试 VIP 等级特勤礼包领取与重复领取校验")
    void testLevelPackClaim() {
        Player player = createTestPlayer("vip_test_packs", 30);
        Long playerId = player.getId();

        // VIP 0 不能领 VIP 1 礼包
        assertThrows(IllegalArgumentException.class, () -> vipService.claimLevelPack(playerId, 1));

        // 充值 ¥6 -> VIP 1
        vipService.onRechargeSuccess(playerId, 600L);

        // 领取 VIP 1 礼包
        var claimRes = vipService.claimLevelPack(playerId, 1);
        assertTrue((Boolean) claimRes.get("success"));

        // 重复领取抛出异常
        assertThrows(IllegalArgumentException.class, () -> vipService.claimLevelPack(playerId, 1));

        // 充值到 VIP 3 (¥98 = 9800 cents)
        vipService.onRechargeSuccess(playerId, 9200L);

        // 一键领取所有未领取的礼包 (VIP 2 与 VIP 3)
        var claimAllRes = vipService.claimAllLevelPacks(playerId);
        assertTrue((Boolean) claimAllRes.get("success"));
        List<?> claimed = (List<?>) claimAllRes.get("claimedLevels");
        assertEquals(2, claimed.size());
        assertTrue(claimed.contains(2));
        assertTrue(claimed.contains(3));
    }

    @Test
    @DisplayName("测试微里程碑军备箱领取 (VIP 7~10 区间)")
    void testMicroMilestoneClaim() {
        Player player = createTestPlayer("vip_test_milestone", 30);
        Long playerId = player.getId();

        // 充值 ¥1500 (150000 cents)
        vipService.onRechargeSuccess(playerId, 150000L);

        // 领取 150000 微里程碑
        var claimRes = vipService.claimMilestoneReward(playerId, 150000L);
        assertTrue((Boolean) claimRes.get("success"));

        // 重复领取微里程碑应抛出异常
        assertThrows(IllegalArgumentException.class, () -> vipService.claimMilestoneReward(playerId, 150000L));

        // 未达到的微里程碑不能领取
        assertThrows(IllegalArgumentException.class, () -> vipService.claimMilestoneReward(playerId, 200000L));
    }

    @Test
    @DisplayName("测试 VIP 6 全域安抚：每日免费大赦与黄金批量安抚")
    void testAllCitiesAppease() {
        Player player = createTestPlayer("vip_test_appease", 30);
        Long playerId = player.getId();

        // VIP 0 未解锁全域安抚
        assertThrows(IllegalArgumentException.class, () -> vipService.allCitiesAppease(playerId));

        // 充值 ¥648 (64800 cents) -> VIP 6
        vipService.onRechargeSuccess(playerId, 64800L);

        // 调整城市民怨与民心
        CityEconomy eco = cityScope.economy(playerId);
        eco.setMorale(50);
        eco.setResentment(30);
        cityScope.saveEconomy(playerId);

        // 首次全域安抚：触发免费大赦（民心 100，民怨 0）
        var res1 = vipService.allCitiesAppease(playerId);
        assertTrue((Boolean) res1.get("success"));
        assertEquals("free_amnesty", res1.get("action"));

        CityEconomy afterAmnesty = cityScope.economy(playerId);
        assertEquals(100, afterAmnesty.getMorale());
        assertEquals(0, afterAmnesty.getResentment());

        // 当天第二次全域安抚：因免费大赦已使用，转入黄金批量赈民
        afterAmnesty.setMorale(80);
        afterAmnesty.setResentment(10);
        cityScope.saveEconomy(playerId);

        // 确保黄金充足
        giveResources(playerId, 1000, 1000, 1000, 500, 50000);

        var res2 = vipService.allCitiesAppease(playerId);
        assertTrue((Boolean) res2.get("success"));
        assertEquals("batch_gold", res2.get("action"));
    }

    @Test
    @DisplayName("测试通用军官秘籍 1:1 无损兑换")
    void testUniversalSkillBookConversion() {
        Player player = createTestPlayer("vip_test_skillbook", 30);
        Long playerId = player.getId();

        // 升至 VIP 7 (128000 cents)
        vipService.onRechargeSuccess(playerId, 128000L);

        // 增加 5 本通用秘籍
        depotService.addItem(playerId, "universalSkillBook", 5);

        // 兑换 2 本为全军冲锋 (frenzy)
        var convRes = vipService.convertUniversalSkillBook(playerId, "frenzy", 2);
        assertTrue((Boolean) convRes.get("success"));

        // 验证剩余通用秘籍为 3
        PlayerItem remainingUniversal = playerItemRepository.findByPlayerIdAndItemKey(playerId, "universalSkillBook").orElseThrow();
        assertEquals(3, remainingUniversal.getCount());

        // 验证目标技能书入库且数量为 2
        PlayerItem targetSkillBook = playerItemRepository.findByPlayerIdAndItemKey(playerId, "skillBook_frenzy").orElseThrow();
        assertEquals(2, targetSkillBook.getCount());
    }

    @Test
    @DisplayName("测试 VIP 5 每城双科技并行研发与全战区单一科技互斥")
    void testTechDualQueueAndMutex() {
        Player player = createTestPlayer("vip_test_tech", 30);
        Long playerId = player.getId();

        // 建设 5 级研究所 (lab) 与 5 级城墙 (wall)
        Building lab = new Building();
        lab.setPlayerId(playerId);
        lab.setCitySlot(0);
        lab.setType("lab");
        lab.setLevel(5);
        buildingRepository.save(lab);

        Building wall = new Building();
        wall.setPlayerId(playerId);
        wall.setCitySlot(0);
        wall.setType("wall");
        wall.setLevel(5);
        buildingRepository.save(wall);

        Building academy = new Building();
        academy.setPlayerId(playerId);
        academy.setCitySlot(0);
        academy.setType("academy");
        academy.setLevel(5);
        buildingRepository.save(academy);

        // 充足资源
        giveResources(playerId, 500000, 500000, 500000, 500000, 500000);

        // VIP 0 只能研发 1 项科技 (military_theory)
        var t1 = techService.startResearch(playerId, "military_theory");
        assertTrue((Boolean) t1.get("success"), () -> "t1 failed: " + t1.get("message"));

        // 第二项科技失败（VIP 0 上限 1 队）
        var t2 = techService.startResearch(playerId, "wall_reinforcement");
        assertFalse((Boolean) t2.get("success"));
        assertTrue(((String) t2.get("message")).contains("队列已达上限"));

        // 升级到 VIP 5 (¥328 = 32800 cents)
        vipService.onRechargeSuccess(playerId, 32800L);

        // 现在可以开启第二项科技 (wall_reinforcement)
        var t2Vip = techService.startResearch(playerId, "wall_reinforcement");
        assertTrue((Boolean) t2Vip.get("success"), () -> "t2Vip failed: " + t2Vip.get("message"));

        // 但是不能重复研发同一项科技（全战区单一科技互斥）
        var tDuplicate = techService.startResearch(playerId, "military_theory");
        assertFalse((Boolean) tDuplicate.get("success"));
        assertTrue(((String) tDuplicate.get("message")).contains("全战区科研互斥"));
    }

    @Test
    @DisplayName("测试建筑施工队数量特许与建造工期加速")
    void testBuildQueueAndSpeedBonus() {
        Player player = createTestPlayer("vip_test_build", 30);

        // VIP 0: 施工队上限 3
        assertEquals(3, vipService.getBuildQueueLimit(player));
        assertEquals(0.0, vipService.getBuildSpeedBonus(player));

        // 充值 ¥6 -> VIP 1: 施工队上限提升至 5
        vipService.onRechargeSuccess(player.getId(), 600L);
        Player p1 = playerRepository.findById(player.getId()).orElseThrow();
        assertEquals(5, vipService.getBuildQueueLimit(p1));

        // 充值到 VIP 5: 施工加速 8%
        vipService.onRechargeSuccess(player.getId(), 32200L);
        Player p5 = playerRepository.findById(player.getId()).orElseThrow();
        assertEquals(0.08, vipService.getBuildSpeedBonus(p5), 0.001);
    }

    @Test
    @DisplayName("测试伤兵营黄金救治 VIP 阶梯尊享折扣")
    void testWoundedGoldDiscount() {
        Player player = createTestPlayer("vip_test_hospital", 30);

        // VIP 0: 无折扣
        assertEquals(0.0, vipService.getWoundedGoldDiscount(player));

        // VIP 3: 20% 折扣
        vipService.onRechargeSuccess(player.getId(), 9800L);
        Player p3 = playerRepository.findById(player.getId()).orElseThrow();
        assertEquals(0.20, vipService.getWoundedGoldDiscount(p3), 0.001);

        // 验证 woundedService 计算出的折后黄金费用
        long origCost = woundedService.goldCost("infantry", 100);
        long discountedCost = woundedService.goldCost(player.getId(), "infantry", 100);
        assertTrue(discountedCost < origCost);
        assertEquals((long) Math.ceil(origCost * 0.8), discountedCost);

        // VIP 10: 50% 折扣
        vipService.onRechargeSuccess(player.getId(), 1000000L);
        Player p10 = playerRepository.findById(player.getId()).orElseThrow();
        assertEquals(0.50, vipService.getWoundedGoldDiscount(p10), 0.001);
        long halfCost = woundedService.goldCost(player.getId(), "infantry", 100);
        assertEquals((long) Math.ceil(origCost * 0.5), halfCost);
    }
}
