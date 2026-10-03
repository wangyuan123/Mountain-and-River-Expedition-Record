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

import java.util.List;
import java.util.Map;

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

        List<Map<String, Object>> npcs = adminSpawnerService.spawnNpcCities(2, 3, 5, "admin", null);
        assertEquals(2, npcs.size());

        assertTrue(logRepository.count() > 0, "应有操作审计日志产生");
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
