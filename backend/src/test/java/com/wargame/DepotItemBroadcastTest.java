package com.wargame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.config.GameWebSocketHandler;
import com.wargame.model.constants.ItemDef;
import com.wargame.model.constants.OfficerEquipmentDef;
import com.wargame.model.entity.ChatMessage;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.ChatMessageRepository;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.ChatService;
import com.wargame.service.DepotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DepotItemBroadcastTest extends BaseServiceTest {
    @Autowired private DepotService depot;
    @Autowired private ChatService chat;
    @Autowired private ChatMessageRepository messages;
    @Autowired private PlayerItemRepository items;
    @Autowired private PlatformTransactionManager transactions;
    @MockBean private GameWebSocketHandler sockets;

    static Stream<String> equipmentBoxes() {
        return ItemDef.ITEMS.keySet().stream().filter(key -> key.startsWith("box_"));
    }

    static Stream<String> stageGifts() {
        return DepotService.STAGE_GIFTS.keySet().stream();
    }

    static Stream<String> resourceBoxes() {
        return Stream.of("goldBox", "resBox", "steelPack", "supplyPack", "newbiePack", "resourcePack500w");
    }

    @ParameterizedTest
    @MethodSource("equipmentBoxes")
    void equipmentBoxAnnouncesAllTwelveAwardedItems(String itemId) {
        Player player = createTestPlayer();
        player.setDisplayName("烽原统帅");
        playerRepository.save(player);
        giveItem(player.getId(), itemId);
        long previous = messages.count();

        assertEquals(true, depot.useItem(player.getId(), itemId, null, null).get("success"));

        ChatMessage message = onlyNewMessage(previous);
        assertTrue(message.getContent().startsWith("玩家「烽原统帅」打开了「" + ItemDef.ITEMS.get(itemId).name() + "」，获得："));
        var equipment = OfficerEquipmentDef.ITEMS.values().stream()
                .filter(def -> def.setKey().equals(itemId.substring(4))).toList();
        assertEquals(12, equipment.size());
        for (var def : equipment) {
            assertTrue(message.getContent().contains(def.name() + "×1"));
            assertEquals(1, items.findByPlayerIdAndItemKey(player.getId(), def.key()).orElseThrow().getCount());
        }
        verify(sockets, never()).broadcast(anyString());
    }

    @ParameterizedTest
    @MethodSource("stageGifts")
    void stageGiftAnnouncesEveryResourceAndExtraWithoutTruncation(String itemId) {
        Player player = createTestPlayer("stage_gift_private_login", 30);
        player.setDisplayName("统帅".repeat(25));
        playerRepository.save(player);
        giveItem(player.getId(), itemId);
        long previous = messages.count();

        assertEquals(true, depot.useItem(player.getId(), itemId, null, null).get("success"));

        ChatMessage message = onlyNewMessage(previous);
        var gift = DepotService.STAGE_GIFTS.get(itemId);
        assertTrue(message.getContent().startsWith("玩家「" + player.getDisplayName() + "」打开了「" + gift.name() + "」"));
        assertFalse(message.getContent().contains(player.getUsername()));
        Map.of("粮食", gift.food(), "钢铁", gift.steel(), "石油", gift.oil(),
                "稀矿", gift.rare(), "黄金", gift.gold(), "钻石", gift.diamond()).forEach((name, amount) ->
                assertTrue(message.getContent().contains(name + "×" + amount), message.getContent()));
        gift.extraItems().forEach((key, count) -> {
            assertTrue(message.getContent().contains(ItemDef.ITEMS.get(key).name() + "×" + count));
            assertEquals(count, items.findByPlayerIdAndItemKey(player.getId(), key).orElseThrow().getCount());
        });
        assertTrue(message.getContent().length() > ChatService.MAX_LENGTH);
        assertTrue(message.getContent().endsWith("。"));
        assertTrue(chat.history().stream().anyMatch(entry -> entry.content().equals(message.getContent())));
    }

    @ParameterizedTest
    @MethodSource("resourceBoxes")
    void resourceBoxAnnouncesActualIncreaseIncludingRandomGold(String itemId) {
        Player player = createTestPlayer();
        giveItem(player.getId(), itemId);
        var before = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        long food = before.getFood();
        long steel = before.getSteel();
        long oil = before.getOil();
        long rare = before.getRare();
        long gold = before.getGold();
        long previous = messages.count();

        assertEquals(true, depot.useItem(player.getId(), itemId, null, null).get("success"));

        ChatMessage message = onlyNewMessage(previous);
        assertTrue(message.getContent().contains("打开了「" + ItemDef.ITEMS.get(itemId).name() + "」"));
        var after = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        Map.of("粮食", after.getFood() - food, "钢铁", after.getSteel() - steel,
                "石油", after.getOil() - oil, "稀矿", after.getRare() - rare,
                "黄金", after.getGold() - gold).forEach((name, gained) -> {
            if (gained > 0) assertTrue(message.getContent().contains(name + "×" + gained));
            else assertFalse(message.getContent().contains(name + "×"));
        });
        if ("goldBox".equals(itemId)) assertTrue(after.getGold() - gold >= 1000 && after.getGold() - gold <= 5000);
    }

    @Test
    void itemAnnouncementWithoutCommanderNameDoesNotExposeLogin() {
        Player player = createTestPlayer("box_private_login", 30);
        String content = chat.sendItemOpened(player.getId(), "黄金箱", List.of("黄金×10")).content();
        assertEquals("玩家「统帅" + player.getId() + "」打开了「黄金箱」，获得：黄金×10。", content);
        assertFalse(content.contains(player.getUsername()));
    }

    @Test
    void exhaustedAndInvalidRequestsAndOrdinaryItemsDoNotAnnounce() {
        Player player = createTestPlayer();
        long previous = messages.count();
        for (String itemId : List.of("newbiePack", "stage_gift_8", "box_marshal_military", "unknown")) {
            assertEquals(false, depot.useItem(player.getId(), itemId, null, null).get("success"));
        }
        giveItem(player.getId(), "shield");
        assertEquals(true, depot.useItem(player.getId(), "shield", null, null).get("success"));
        assertEquals(previous, messages.count());
        verify(sockets, never()).broadcast(anyString());
    }

    @Test
    void missingResourcesRefundItemWithoutAnnouncement() {
        Player player = createTestPlayer();
        resourcesRepository.delete(resourcesRepository.findByPlayerId(player.getId()).orElseThrow());
        long previous = messages.count();
        for (String itemId : List.of("newbiePack", "stage_gift_1")) {
            giveItem(player.getId(), itemId);
            assertEquals(false, depot.useItem(player.getId(), itemId, null, null).get("success"));
            assertEquals(1, items.findByPlayerIdAndItemKey(player.getId(), itemId).orElseThrow().getCount());
        }
        assertEquals(previous, messages.count());
    }

    @Test
    void cappedResourceOnlyAnnouncesTheActualIncrease() {
        Player player = createTestPlayer();
        var resources = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        resources.setSteel(Long.MAX_VALUE - 7);
        resourcesRepository.save(resources);
        giveItem(player.getId(), "steelPack");
        long previous = messages.count();

        depot.useItem(player.getId(), "steelPack", null, null);

        assertTrue(onlyNewMessage(previous).getContent().endsWith("获得：钢铁×7。"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollbackRestoresItemAndRewardsAndOnlyCommitBroadcasts() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        Long playerId = transaction.execute(status -> {
            Long id = createTestPlayer("item-broadcast-transaction", 30).getId();
            giveItem(id, "steelPack");
            return id;
        });
        long previous = messages.count();
        clearInvocations(sockets);

        transaction.executeWithoutResult(status -> {
            assertEquals(true, depot.useItem(playerId, "steelPack", null, null).get("success"));
            assertEquals(previous + 1, messages.count());
            verify(sockets, never()).broadcast(anyString());
            status.setRollbackOnly();
        });
        assertEquals(previous, messages.count());
        assertEquals(1, items.findByPlayerIdAndItemKey(playerId, "steelPack").orElseThrow().getCount());
        assertEquals(1000L, resourcesRepository.findByPlayerId(playerId).orElseThrow().getSteel());
        verify(sockets, never()).broadcast(anyString());

        transaction.executeWithoutResult(status -> {
            assertEquals(true, depot.useItem(playerId, "steelPack", null, null).get("success"));
            verify(sockets, never()).broadcast(anyString());
        });
        ChatMessage message = onlyNewMessage(previous);
        var broadcast = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sockets, times(1)).broadcast(broadcast.capture());
        var payload = new ObjectMapper().readTree(broadcast.getValue());
        assertEquals("chat", payload.path("type").asText());
        assertEquals("system", payload.path("data").path("type").asText());
        assertEquals(message.getContent(), payload.path("data").path("content").asText());
        assertEquals(21000L, resourcesRepository.findByPlayerId(playerId).orElseThrow().getSteel());
        assertEquals(false, depot.useItem(playerId, "steelPack", null, null).get("success"));
        assertEquals(previous + 1, messages.count());
        verify(sockets, times(1)).broadcast(anyString());
    }

    private void giveItem(Long playerId, String itemId) {
        items.save(new PlayerItem(null, playerId, itemId, 1, System.currentTimeMillis()));
    }

    private ChatMessage onlyNewMessage(long previous) {
        assertEquals(previous + 1, messages.count());
        ChatMessage message = messages.findTop50ByOrderByCreatedAtDesc().get(0);
        assertNull(message.getPlayerId());
        assertEquals("系统", message.getUsername());
        return message;
    }
}
