package com.wargame;

import com.wargame.model.entity.Officer;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerItem;
import com.wargame.model.entity.Resources;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.OfficerService;
import com.wargame.service.DepotService;
import com.wargame.service.NameChangePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class OfficerRenameTest extends BaseServiceTest {

    @Autowired
    private OfficerService officerService;

    @Autowired
    private DepotService depotService;

    @Autowired
    private PlayerItemRepository playerItemRepository;

    @Test
    public void testRenameWithRenameCard() {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "idle", 50, 40, 30);
        officer.setName("旧名字");
        officerRepository.save(officer);

        // 给玩家 1 张改名卡
        PlayerItem card = new PlayerItem();
        card.setPlayerId(player.getId());
        card.setItemKey("renameCard");
        card.setCount(1);
        card.setUpdatedAt(System.currentTimeMillis());
        playerItemRepository.save(card);

        Map<String, Object> res = officerService.rename(player.getId(), officer.getId(), "常胜将军");
        assertTrue((Boolean) res.get("success"), "改名应当成功");
        assertTrue((Boolean) res.get("usedCard"), "应当消耗了军官改名卡");
        assertEquals("常胜将军", res.get("name"));
        assertTrue(((String) res.get("message")).contains("消耗 1 张军官改名卡"));

        Officer updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals("常胜将军", updated.getName());

        PlayerItem afterCard = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "renameCard").orElse(null);
        assertNotNull(afterCard);
        assertEquals(0, afterCard.getCount(), "军官改名卡应被扣除为 0");
    }

    @Test
    public void testRenameWithoutCardUsesGold() {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "idle", 50, 40, 30);
        officer.setName("原将领");
        officerRepository.save(officer);

        // 玩家初始资源有 100000 黄金，无军官改名卡
        Resources resBefore = resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).orElseThrow();
        int goldBefore = resBefore.getGold();

        Map<String, Object> res = officerService.rename(player.getId(), officer.getId(), "虎豹骑统领");
        assertTrue((Boolean) res.get("success"), "无卡但金币充足时改名应当成功");
        assertFalse((Boolean) res.get("usedCard"), "不应消耗军官改名卡");
        assertEquals("虎豹骑统领", res.get("name"));
        assertTrue(((String) res.get("message")).contains("消耗 60 黄金"));

        Officer updated = officerRepository.findById(officer.getId()).orElseThrow();
        assertEquals("虎豹骑统领", updated.getName());

        Resources resAfter = resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).orElseThrow();
        assertEquals(goldBefore - 60, resAfter.getGold(), "应扣除 60 黄金");
    }

    @Test
    public void testRenameInsufficientCardAndGold() {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "idle", 50, 40, 30);

        // 清空黄金，无军官改名卡
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).orElseThrow();
        res.setGold(50);
        resourcesRepository.save(res);

        Map<String, Object> result = officerService.rename(player.getId(), officer.getId(), "新名");
        assertFalse((Boolean) result.get("success"), "金币与卡都不足时应失败");
        assertTrue(((String) result.get("message")).contains("军官改名卡或黄金不足"));
    }

    @Test
    public void testRenameValidation() {
        Player player = createTestPlayer();
        Officer officer = createOfficer(player.getId(), "idle", 50, 40, 30);
        officer.setName("现有名字");
        officerRepository.save(officer);

        // 空名字
        Map<String, Object> emptyRes = officerService.rename(player.getId(), officer.getId(), "   ");
        assertFalse((Boolean) emptyRes.get("success"));
        assertTrue(((String) emptyRes.get("message")).contains("不能为空"));

        // 超过12个字
        Map<String, Object> longRes = officerService.rename(player.getId(), officer.getId(), "一二三四五六七八九十一二三");
        assertFalse((Boolean) longRes.get("success"));
        assertTrue(((String) longRes.get("message")).contains("最多12个字符"));

        // 与原名相同
        Map<String, Object> sameRes = officerService.rename(player.getId(), officer.getId(), "现有名字");
        assertFalse((Boolean) sameRes.get("success"));
        assertTrue(((String) sameRes.get("message")).contains("与当前军官名称相同"));

        // 可见符号允许使用，危险标记由页面输出端做 HTML 转义。
        Map<String, Object> symbolRes = officerService.rename(player.getId(), officer.getId(), "李白·天上来☆");
        assertTrue((Boolean) symbolRes.get("success"));
        assertEquals("李白·天上来☆", symbolRes.get("name"));

        Map<String, Object> controlRes = officerService.rename(player.getId(), officer.getId(), "张三\n将军");
        assertFalse((Boolean) controlRes.get("success"));
        assertTrue(((String) controlRes.get("message")).contains("不可见字符"));

    }

    @Test
    public void testRenameOfficerNotFoundOrNotOwned() {
        Player player1 = createTestPlayer("p1", 30);
        Player player2 = createTestPlayer("p2", 30);

        Officer officer2 = createOfficer(player2.getId(), "idle", 50, 40, 30);

        Map<String, Object> result = officerService.rename(player1.getId(), officer2.getId(), "抢名字");
        assertFalse((Boolean) result.get("success"));
        assertEquals("军官不存在", result.get("message"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void dailyLimitIsSharedByDirectRenameAndRenameCardWithoutChargingAgain() {
        Player player = createTestPlayer();
        Officer first = createOfficer(player.getId(), "idle", 50, 40, 30);
        Officer second = createOfficer(player.getId(), "idle", 50, 40, 30);
        PlayerItem card = new PlayerItem();
        card.setPlayerId(player.getId());
        card.setItemKey("renameCard");
        card.setCount(2);
        card.setUpdatedAt(System.currentTimeMillis());
        playerItemRepository.save(card);

        assertEquals(true, officerService.rename(player.getId(), first.getId(), "新将领一").get("success"));
        List<Map<String, Object>> officers = (List<Map<String, Object>>) gameStateService.getGameState(player.getId()).get("officers");
        Map<String, Object> firstState = officers.stream().filter(o -> first.getId().equals(o.get("id"))).findFirst().orElseThrow();
        assertTrue(((Number) firstState.get("nameRenameAvailableAt")).longValue() > System.currentTimeMillis());

        int goldBefore = resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).orElseThrow().getGold();
        assertEquals(false, officerService.rename(player.getId(), first.getId(), "再次改名").get("success"));
        assertEquals(false, depotService.useItem(player.getId(), "renameCard", first.getId(), "改名卡重试").get("success"));
        assertEquals(goldBefore, resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).orElseThrow().getGold());
        assertEquals(1, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "renameCard").orElseThrow().getCount());

        assertEquals(true, depotService.useItem(player.getId(), "renameCard", second.getId(), "另一将领").get("success"));
        assertEquals(0, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "renameCard").orElseThrow().getCount());

        Officer storedFirst = officerRepository.findById(first.getId()).orElseThrow();
        storedFirst.setNameRenamedAt(System.currentTimeMillis() - 24 * 60 * 60 * 1000L);
        officerRepository.saveAndFlush(storedFirst);
        assertEquals(0L, NameChangePolicy.nextAllowedAt(storedFirst.getNameRenamedAt(), System.currentTimeMillis()));
        assertEquals(true, officerService.rename(player.getId(), first.getId(), "次日改名").get("success"));
        assertEquals(goldBefore - 60, resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).orElseThrow().getGold());
    }
}
