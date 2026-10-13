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
}
