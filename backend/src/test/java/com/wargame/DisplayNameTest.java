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

    @Test
    void acceptsVisibleSymbolsAndEmojiButRejectsInvisibleCharacters() {
        assertEquals("大河之剑天上来-李白☆", DisplayNamePolicy.validate(" 大河之剑天上来-李白☆ ", 12, "军官名称"));
        assertEquals("剑客🎖️", DisplayNamePolicy.validate("剑客🎖️", 8, "统帅名"));
        assertEquals("<勇者>", DisplayNamePolicy.validate("<勇者>", 8, "统帅名"));
        assertThrows(IllegalArgumentException.class, () -> DisplayNamePolicy.validate("张三\n将军", 12, "军官名称"));
        assertThrows(IllegalArgumentException.class, () -> DisplayNamePolicy.validate("张三\n", 12, "军官名称"));
        assertThrows(IllegalArgumentException.class, () -> DisplayNamePolicy.validate("\t张三", 12, "军官名称"));
        assertThrows(IllegalArgumentException.class, () -> DisplayNamePolicy.validate("张三\u202e将军", 12, "军官名称"));
        assertThrows(IllegalArgumentException.class, () -> DisplayNamePolicy.validate("😀".repeat(9), 8, "统帅名"));
    }

    @Test
    void savesGameDisplayNameWithoutChangingLoginUsername() {
        Player player = createTestPlayer("login-user", 30);
        assertEquals("login-user", ((Map<?, ?>) gameStateService.getGameState(player.getId()).get("player")).get("name"));

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
}
