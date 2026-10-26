package com.wargame;

import com.wargame.model.constants.DisplayNamePolicy;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.Officer;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.DepotService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DisplayNameTest extends BaseServiceTest {
    @Autowired private EntityManager entityManager;
    @Autowired private DepotService depotService;
    @Autowired private PlayerItemRepository playerItemRepository;
    @Autowired private com.wargame.service.AuthService authService;

    @Test
    void acceptsVisibleSymbolsAndEmojiButRejectsInvisibleCharacters() {
        assertEquals("大河之剑天上来-李白☆", DisplayNamePolicy.validate(" 大河之剑天上来-李白☆ ", 12, "军官名称"));
        assertEquals("剑客🎖️", DisplayNamePolicy.validate("剑客🎖️", 8, "统帅名"));
        assertEquals("<勇者>", DisplayNamePolicy.validate("<勇者>", 8, "统帅名"));
        assertEquals("钢铁要塞☆", DisplayNamePolicy.validate(" 钢铁要塞☆ ", 12, "城市名称"));
        assertEquals("<前哨站>", DisplayNamePolicy.validate("<前哨站>", 12, "城市名称"));
        assertEquals("烈火城🎖️", DisplayNamePolicy.validate("烈火城🎖️", 12, "城市名称"));
        assertThrows(IllegalArgumentException.class, () -> DisplayNamePolicy.validate("张三\n将军", 12, "军官名称"));
        assertThrows(IllegalArgumentException.class, () -> DisplayNamePolicy.validate("张三\n", 12, "军官名称"));
        assertThrows(IllegalArgumentException.class, () -> DisplayNamePolicy.validate("\t张三", 12, "军官名称"));
        assertThrows(IllegalArgumentException.class, () -> DisplayNamePolicy.validate("张三\u202e将军", 12, "军官名称"));
        assertThrows(IllegalArgumentException.class, () -> DisplayNamePolicy.validate("😀".repeat(9), 8, "统帅名"));
        assertThrows(IllegalArgumentException.class, () -> DisplayNamePolicy.validate("钢铁\n要塞", 12, "城市名称"));
        assertThrows(IllegalArgumentException.class, () -> DisplayNamePolicy.validate("\t要塞", 12, "城市名称"));
        assertThrows(IllegalArgumentException.class, () -> DisplayNamePolicy.validate("要塞\u202e前哨", 12, "城市名称"));
        assertThrows(IllegalArgumentException.class, () -> DisplayNamePolicy.validate("超长城市名称一二三四五六七八九", 12, "城市名称"));
    }

    @Test
    void savesGameDisplayNameWithoutChangingLoginUsername() {
        Player player = createTestPlayer("login-user", 30);
        assertEquals("login-user", ((Map<?, ?>) gameStateService.getGameState(player.getId()).get("player")).get("name"));

        PlayerItem card = new PlayerItem();
        card.setPlayerId(player.getId());
        card.setItemKey("commanderRenameCard");
        card.setCount(1);
        card.setUpdatedAt(System.currentTimeMillis());
        playerItemRepository.save(card);

        gameStateService.setDisplayName(player.getId(), "剑客☆🎖️");
        entityManager.flush();
        entityManager.clear();

        Player saved = playerRepository.findById(player.getId()).orElseThrow();
        assertEquals("login-user", saved.getUsername());
        assertEquals("剑客☆🎖️", saved.getDisplayName());
        assertEquals("剑客☆🎖️", ((Map<?, ?>) gameStateService.getGameState(player.getId()).get("player")).get("name"));
        assertThrows(IllegalArgumentException.class,
                () -> gameStateService.setDisplayName(player.getId(), "无形\u200b人"));
    }

    @Test
    void depotRenameCardUsesTheSameVisibleCharacterPolicy() {
        Player player = createTestPlayer("depot-rename", 30);
        Officer officer = createOfficer(player.getId(), "idle", 50, 40, 30);
        PlayerItem card = new PlayerItem();
        card.setPlayerId(player.getId());
        card.setItemKey("renameCard");
        card.setCount(1);
        card.setUpdatedAt(System.currentTimeMillis());
        playerItemRepository.save(card);

        assertFalse((Boolean) depotService.useItem(player.getId(), "renameCard", officer.getId(), "隐\u200b形").get("success"));
        assertEquals(1, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "renameCard").orElseThrow().getCount());
        assertTrue((Boolean) depotService.useItem(player.getId(), "renameCard", officer.getId(), "剑客☆").get("success"));
        assertEquals("剑客☆", officerRepository.findById(officer.getId()).orElseThrow().getName());
    }

    @Test
    void cityNameSupportsSpecialCharactersAndRejectsInvisibleCharacters() {
        Player player = createTestPlayer("city-rename-special", 30);
        PlayerItem card = new PlayerItem();
        card.setPlayerId(player.getId());
        card.setItemKey("cityRenameCard");
        card.setCount(1);
        card.setUpdatedAt(System.currentTimeMillis());
        playerItemRepository.save(card);

        gameStateService.setCityName(player.getId(), "烈火·前哨☆");
        entityManager.flush();
        entityManager.clear();

        Player saved = playerRepository.findById(player.getId()).orElseThrow();
        assertEquals("烈火·前哨☆", saved.getCityName());

        assertThrows(IllegalArgumentException.class,
                () -> gameStateService.setCityName(player.getId(), "隐\u200b形城"));
    }

    @Test
    void registersNewPlayerWithDefaultCommanderName() {
        createTestWorld();
        Player existing = new Player();
        existing.setUsername("existing_user_reg");
        existing.setDisplayName("远征统帅_123456");
        existing.setPasswordHash("pass");
        playerRepository.save(existing);

        var regResult = authService.register("new_reg_user", "password123", new org.springframework.mock.web.MockHttpServletRequest());
        Player registered = playerRepository.findById(regResult.playerId()).orElseThrow();

        assertNotNull(registered.getDisplayName());
        assertTrue(registered.getDisplayName().matches("^远征统帅_\\d{6}$"),
                "新注册玩家统帅名默认值必须为 '远征统帅_xxx' 且为6位数字，实际为: " + registered.getDisplayName());
        assertNotEquals("远征统帅_123456", registered.getDisplayName(), "统帅名不得与已存在玩家重复");

        Map<?, ?> state = gameStateService.getGameState(registered.getId());
        assertEquals(registered.getDisplayName(), ((Map<?, ?>) state.get("player")).get("name"));
    }
}
