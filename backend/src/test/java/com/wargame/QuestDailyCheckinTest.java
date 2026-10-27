package com.wargame;

import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerItem;
import com.wargame.model.entity.Resources;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.ResourcesRepository;
import com.wargame.service.CityScope;
import com.wargame.service.quest.QuestService;
import com.wargame.util.GameClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("每日任务与军政签到系统测试")
public class QuestDailyCheckinTest extends BaseServiceTest {

    @Autowired
    private QuestService questService;

    @Autowired
    private ResourcesRepository resourcesRepository;

    @Autowired
    private PlayerItemRepository playerItemRepository;

    @Autowired
    private CityScope cityScope;

    @Test
    @DisplayName("每日签到按游戏北京时间刷新，不受服务器自然日影响")
    void dailyCheckinUsesGameClockDate() {
        Player player = createTestPlayer("test_checkin_game_date", 30);
        player.setLastDailyCheckinDate(LocalDate.of(2026, 10, 26));
        playerRepository.saveAndFlush(player);

        // 15:30 UTC 对应北京时间 10 月 26 日 23:30，仍不能提前领取 10 月 27 日奖励。
        try (AutoCloseable ignored = GameClock.use(() -> Instant.parse("2026-10-26T15:30:00Z").toEpochMilli())) {
            assertEquals("2026-10-26", questService.getDailyCheckinStatus(player.getId()).get("date"));
            assertEquals(true, questService.getDailyCheckinStatus(player.getId()).get("claimed"));
            assertThrows(IllegalArgumentException.class, () -> questService.claimDailyCheckin(player.getId()));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    @DisplayName("测试每日军政点卯签到状态与自主领受流程")
    void testDailyCheckinStatusAndClaim() {
        Player player = createTestPlayer("test_checkin_player_1", 30);
        Long playerId = player.getId();

        // 初始资源已在 createTestPlayer 中设定好
        Resources initialRes = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElseThrow();
        long baseGold = initialRes.getGold();
        long baseFood = initialRes.getFood();

        // 1. 初始状态：未签到，奖励列表包含军官改名卡与城市改名卡
        Map<String, Object> initialStatus = questService.getDailyCheckinStatus(playerId);
        assertNotNull(initialStatus);
        assertEquals(false, initialStatus.get("claimed"));
        assertEquals(LocalDate.now().toString(), initialStatus.get("date"));

        @SuppressWarnings("unchecked")
        Map<String, Integer> rewards = (Map<String, Integer>) initialStatus.get("rewards");
        assertEquals(1, rewards.get("renameCard"));
        assertEquals(1, rewards.get("cityRenameCard"));
        assertEquals(2000, rewards.get("gold"));
        assertEquals(5000, rewards.get("food"));
        assertEquals(1, rewards.get("speedUp10m"));

        // 2. 玩家自主领受签到奖励
        Map<String, Object> claimRes = questService.claimDailyCheckin(playerId);
        assertTrue((Boolean) claimRes.get("success"));

        // 验证道具成功入库
        PlayerItem renameCard = playerItemRepository.findByPlayerIdAndItemKey(playerId, "renameCard").orElseThrow();
        assertEquals(1, renameCard.getCount());
        PlayerItem cityRenameCard = playerItemRepository.findByPlayerIdAndItemKey(playerId, "cityRenameCard").orElseThrow();
        assertEquals(1, cityRenameCard.getCount());
        PlayerItem speedUp = playerItemRepository.findByPlayerIdAndItemKey(playerId, "speedUp10m").orElseThrow();
        assertEquals(1, speedUp.getCount());

        // 验证资源增长
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElseThrow();
        assertEquals(baseGold + 2000, res.getGold());
        assertEquals(baseFood + 5000, res.getFood());

        // 3. 再次获取状态：已签到
        Map<String, Object> afterStatus = questService.getDailyCheckinStatus(playerId);
        assertEquals(true, afterStatus.get("claimed"));

        // 4. 当日重复领受：抛出异常拒绝
        assertThrows(IllegalArgumentException.class, () -> questService.claimDailyCheckin(playerId));

        // 5. 跨日后（模拟昨日已领）：可再次领取
        player = playerRepository.findById(playerId).orElseThrow();
        player.setLastDailyCheckinDate(LocalDate.now().minusDays(1));
        playerRepository.save(player);

        Map<String, Object> nextDayStatus = questService.getDailyCheckinStatus(playerId);
        assertEquals(false, nextDayStatus.get("claimed"));

        Map<String, Object> nextClaimRes = questService.claimDailyCheckin(playerId);
        assertTrue((Boolean) nextClaimRes.get("success"));

        assertEquals(2, playerItemRepository.findByPlayerIdAndItemKey(playerId, "renameCard").orElseThrow().getCount());
        assertEquals(2, playerItemRepository.findByPlayerIdAndItemKey(playerId, "cityRenameCard").orElseThrow().getCount());
    }
}
