package com.wargame;

import com.wargame.model.entity.AdminUser;
import com.wargame.model.entity.Player;
import com.wargame.repository.AdminOperationLogRepository;
import com.wargame.repository.AdminUserRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.service.admin.AdminAuthService;
import com.wargame.service.admin.AdminGmService;
import com.wargame.service.admin.AdminPlayerService;
import com.wargame.service.admin.AdminSpawnerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class AdminSystemTest extends BaseServiceTest {

    @Autowired
    private AdminAuthService adminAuthService;
    @Autowired
    private AdminUserRepository adminUserRepository;
    @Autowired
    private AdminPlayerService adminPlayerService;
    @Autowired
    private AdminSpawnerService adminSpawnerService;
    @Autowired
    private AdminGmService adminGmService;
    @Autowired
    private AdminOperationLogRepository logRepository;
    @Autowired
    private com.wargame.repository.WildTileRepository wildTileRepository;
    @Autowired
    private com.wargame.service.MailService mailService;
    @Autowired
    private com.wargame.repository.MailRepository mailRepository;
    @Autowired
    private com.wargame.repository.ArmyUnitRepository armyUnitRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setupAdmin() {
        if (worldMapRepository.count() == 0) {
            createTestWorld();
        }
        if (!adminUserRepository.existsByUsername("admin")) {
            AdminUser admin = new AdminUser();
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode("admin123456"));
            admin.setRole("SUPER_ADMIN");
            admin.setDisabled(0);
            admin.setCreatedAt(System.currentTimeMillis());
            admin.setUpdatedAt(System.currentTimeMillis());
            adminUserRepository.save(admin);
        }
    }

    @Test
    void testAdminLogin() {
        Map<String, Object> res = adminAuthService.login("admin", "admin123456", null);
        assertNotNull(res.get("token"));
        assertEquals("admin", res.get("username"));
        assertEquals("SUPER_ADMIN", res.get("role"));
    }

    @Test
    void testBanAndUnbanPlayer() {
        Player player = new Player();
        player.setUsername("test_ban_user");
        player.setPasswordHash("pass");
        player.setAuthVersion(1L);
        player = playerRepository.save(player);

        adminPlayerService.banPlayer(player.getId(), "测试作弊封禁", 24L, "admin", null);

        Player banned = playerRepository.findById(player.getId()).orElseThrow();
        assertEquals("BANNED", banned.getBanStatus());
        assertEquals("测试作弊封禁", banned.getBanReason());
        assertTrue(banned.isBanned(System.currentTimeMillis()));
        assertEquals(2L, banned.getAuthVersion()); // 校验踢下线版本号增加

        adminPlayerService.unbanPlayer(player.getId(), "admin", null);
        Player unbanned = playerRepository.findById(player.getId()).orElseThrow();
        assertEquals("NORMAL", unbanned.getBanStatus());
        assertFalse(unbanned.isBanned(System.currentTimeMillis()));
    }

    @Test
    void testSpawnBotsAndNpc() {
        List<Map<String, Object>> bots = adminSpawnerService.spawnMockBots(2, "TestBot", 3, "admin", null);
        assertEquals(2, bots.size());

        assertEquals("NPC城市/要塞已取消", assertThrows(IllegalArgumentException.class,
                () -> adminSpawnerService.spawnNpcCities(2, 3, 5, "admin", null)).getMessage());
        assertEquals(0, npcCityRepository.count());

        assertTrue(logRepository.count() > 0, "应有操作审计日志产生");
    }

    @Test
    void testSpawnMockBotsDefaultCommanderName() {
        Player registered1 = new Player();
        registered1.setUsername("existing_commander_user");
        registered1.setDisplayName("远征统帅_888888");
        registered1.setPasswordHash("pass");
        playerRepository.save(registered1);

        Player registered2 = new Player();
        registered2.setUsername("远征统帅_999999");
        registered2.setPasswordHash("pass");
        playerRepository.save(registered2);

        List<Map<String, Object>> bots = adminSpawnerService.spawnMockBots(5, null, 2, "admin", null);
        assertEquals(5, bots.size());

        Set<String> displayNames = new HashSet<>();
        for (Map<String, Object> bot : bots) {
            String displayName = (String) bot.get("displayName");
            assertNotNull(displayName);
            assertTrue(displayName.matches("^远征统帅_\\d{6}$"),
                    "统帅名格式必须为 '远征统帅_xxx' 且数字为6位，实际为: " + displayName);
            assertNotEquals("远征统帅_888888", displayName, "统帅名不得与已注册玩家 displayName 重复");
            assertNotEquals("远征统帅_999999", displayName, "统帅名不得与已注册玩家 username 重复");
            displayNames.add(displayName);
        }
        assertEquals(5, displayNames.size(), "批量生成的统帅名之间不得重复");

        // 验证显式传入 "远征统帅_" 前缀时不会产生双下划线
        List<Map<String, Object>> botsWithUnderscore = adminSpawnerService.spawnMockBots(2, "远征统帅_", 1, "admin", null);
        for (Map<String, Object> bot : botsWithUnderscore) {
            String displayName = (String) bot.get("displayName");
            assertTrue(displayName.matches("^远征统帅_\\d{6}$"), "前缀带下划线时不应出现双下划线: " + displayName);
        }
    }

    @Test
    void testGmCommand() {
        Player player = new Player();
        player.setUsername("test_gm_user");
        player.setPasswordHash("pass");
        player = playerRepository.save(player);

        Map<String, Object> cmdRes = adminGmService.executeCommand("/set_level " + player.getId() + " 15", "admin", null);
        assertNotNull(cmdRes.get("result"));
        Player updated = playerRepository.findById(player.getId()).orElseThrow();
        assertEquals(15, updated.getLevel());
    }

    @Test
    void testSpawnWildTilesAndStats() {
        // 1. 测试生成资源野地
        List<Map<String, Object>> wilds = adminSpawnerService.spawnWildTiles("ALL_RESOURCES", 10, 1, 5, true, "admin", null);
        assertEquals(10, wilds.size());
        for (Map<String, Object> w : wilds) {
            assertNotNull(w.get("id"));
            assertNotNull(w.get("type"));
            assertTrue(List.of("grainfield", "ironworks", "oil", "rarefactory").contains(w.get("type")));
            int level = (Integer) w.get("level");
            assertTrue(level >= 1 && level <= 5);
            assertNotNull(w.get("resType"));
        }

        // 2. 统计检查
        Map<String, Object> stats = adminSpawnerService.getWildStats();
        assertEquals(10L, ((Number) stats.get("totalCount")).longValue());
        assertEquals(10L, ((Number) stats.get("resourceCount")).longValue());
        assertEquals(0L, ((Number) stats.get("occupiedCount")).longValue());
        assertEquals(10L, ((Number) stats.get("unoccupiedCount")).longValue());

        // 3. 测试生成指定类型野地
        List<Map<String, Object>> grainfields = adminSpawnerService.spawnWildTiles("grainfield", 5, 3, 3, false, "admin", null);
        assertEquals(5, grainfields.size());
        for (Map<String, Object> g : grainfields) {
            assertEquals("grainfield", g.get("type"));
            assertEquals(3, g.get("level"));
        }

        // 4. 清理无主野地
        Map<String, Object> clearRes = adminSpawnerService.clearUnoccupiedWilds("admin", null);
        assertEquals(true, clearRes.get("success"));
        assertEquals(15, clearRes.get("deletedCount"));

        Map<String, Object> statsAfterClear = adminSpawnerService.getWildStats();
        assertEquals(0L, ((Number) statsAfterClear.get("totalCount")).longValue());
    }

    @Test
    void testBatchDeleteWildTiles() {
        // 1. 先生成 6 块野地
        List<Map<String, Object>> wilds = adminSpawnerService.spawnWildTiles("ALL_RESOURCES", 6, 1, 3, true, "admin", null);
        assertEquals(6, wilds.size());

        // 2. 获取可删除列表
        List<Map<String, Object>> deletable = adminSpawnerService.getDeletableWildTiles();
        assertTrue(deletable.size() >= 6);

        // 3. 批量删除前 3 块
        List<Long> toDelete = List.of(
                ((Number) wilds.get(0).get("id")).longValue(),
                ((Number) wilds.get(1).get("id")).longValue(),
                ((Number) wilds.get(2).get("id")).longValue()
        );
        Map<String, Object> delRes = adminSpawnerService.deleteWildTiles(toDelete, "admin", null);
        assertEquals(true, delRes.get("success"));
        assertEquals(3, delRes.get("deletedCount"));

        // 4. 校验剩余 3 块
        Map<String, Object> stats = adminSpawnerService.getWildStats();
        assertEquals(3L, ((Number) stats.get("totalCount")).longValue());

        // 5. 一键清理剩余
        Map<String, Object> clearRes = adminSpawnerService.clearUnoccupiedWilds("admin", null);
        assertEquals(3, clearRes.get("deletedCount"));
    }

    @Test
    void testSendCompensationMailWithArmyUnitsAndClaim() {
        Player player = createTestPlayer("test_mail_army_user", 30);

        // 1. 发送包含资源与兵力的补偿邮件 (5000黄金 + 1000步兵 + 50重型坦克)
        List<Map<String, Object>> attachments = List.of(
                Map.of("type", "gold", "qty", 5000),
                Map.of("type", "infantry", "qty", 1000),
                Map.of("type", "htank", "qty", 50)
        );
        Map<String, Object> sendRes = adminGmService.sendCompensationMail("SINGLE", player.getId(), "军备补充邮件", "测试发放兵力", attachments, "admin", null);
        assertTrue(Boolean.TRUE.equals(sendRes.get("success")));

        // 2. 查询该玩家收件箱中的邮件
        List<com.wargame.model.entity.Mail> playerMails = mailRepository.findByToPlayerIdOrderByCreatedAtDesc(player.getId());
        assertFalse(playerMails.isEmpty());
        com.wargame.model.entity.Mail mail = playerMails.get(0);
        assertFalse(mail.getIsClaimed());

        // 3. 玩家领取附件
        // 管理员赠兵也须满足归队城市的兵种条件；失败时整封附件保留待领取。
        int goldBefore = getResources(player.getId()).getGold();
        assertEquals(false, mailService.claimAttach(player.getId(), mail.getId()).get("success"));
        assertFalse(mailRepository.findById(mail.getId()).orElseThrow().getIsClaimed());
        assertEquals(goldBefore, getResources(player.getId()).getGold());
        assertTrue(armyUnitRepository.findByPlayerId(player.getId()).isEmpty());
        createBuilding(player.getId(), "factory", 1);
        createBuilding(player.getId(), "heavyfactory", 6);
        for (String technology : List.of("arm_engine", "attack_tech", "defense_tech")) {
            createTechnology(player.getId(), technology, 4);
        }
        Map<String, Object> claimRes = mailService.claimAttach(player.getId(), mail.getId());
        assertTrue(Boolean.TRUE.equals(claimRes.get("success")));

        // 4. 验证邮件变为已领取
        com.wargame.model.entity.Mail claimedMail = mailRepository.findById(mail.getId()).orElseThrow();
        assertTrue(claimedMail.getIsClaimed());

        // 5. 验证兵力已成功入驻玩家主城驻军
        List<com.wargame.model.entity.ArmyUnit> infantryList = armyUnitRepository.findByPlayerIdAndCitySlotAndType(player.getId(), 0, "infantry");
        assertFalse(infantryList.isEmpty());
        assertEquals(1000, infantryList.get(0).getCount());

        List<com.wargame.model.entity.ArmyUnit> htankList = armyUnitRepository.findByPlayerIdAndCitySlotAndType(player.getId(), 0, "htank");
        assertFalse(htankList.isEmpty());
        assertEquals(50, htankList.get(0).getCount());
    }
}
