package com.wargame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.config.GameWebSocketHandler;
import com.wargame.model.entity.ChatMessage;
import com.wargame.model.entity.Player;
import com.wargame.repository.ChatMessageRepository;
import com.wargame.repository.PlayerVipRewardRepository;
import com.wargame.service.ChatService;
import com.wargame.service.VipService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VipPromotionBroadcastTest extends BaseServiceTest {
    @Autowired private VipService vip;
    @Autowired private ChatService chat;
    @Autowired private ChatMessageRepository messages;
    @Autowired private PlayerVipRewardRepository rewards;
    @Autowired private PlatformTransactionManager transactions;
    @MockBean private GameWebSocketHandler sockets;

    @Test
    void promotionAnnouncesPublicNameAndClaimablePackInWorldHistory() {
        Player player = createTestPlayer("vip-promotion-private-login", 30);
        player.setDisplayName("烽原统帅");
        playerRepository.save(player);
        long previous = messages.count();

        assertEquals(true, vip.onRechargeSuccess(player.getId(), 600L).get("levelUp"));

        ChatMessage message = onlyNewMessage(previous);
        assertEquals("🎖️ 恭喜玩家「烽原统帅」荣升 VIP 1【特遣队指挥官】！获得「VIP 1 晋升尊享大礼包」领取资格，请前往【战时特勤统帅部】领取！", message.getContent());
        assertFalse(message.getContent().contains(player.getUsername()));
        assertFalse(message.getContent().contains("¥"));
        assertFalse(rewards.existsByPlayerIdAndVipLevel(player.getId(), 1));
        assertTrue(chat.history().stream().anyMatch(entry -> entry.content().equals(message.getContent())));
        verify(sockets, never()).broadcast(anyString());
    }

    @Test
    void crossingMultipleLevelsAnnouncesOneMessageAndOnlyNewPackRange() {
        Player player = createTestPlayer("vip-promotion-multiple", 30);
        vip.onRechargeSuccess(player.getId(), 600L);
        long previous = messages.count();

        vip.onRechargeSuccess(player.getId(), 9200L);

        ChatMessage message = onlyNewMessage(previous);
        assertTrue(message.getContent().contains("荣升 VIP 3【战略支柱长官】"));
        assertTrue(message.getContent().contains("「VIP 2～VIP 3 晋升尊享大礼包」领取资格"));
        assertFalse(message.getContent().contains("VIP 1"));
        assertTrue(message.getContent().contains("玩家「统帅" + player.getId() + "」"));
        assertFalse(rewards.existsByPlayerIdAndVipLevel(player.getId(), 2));
        assertFalse(rewards.existsByPlayerIdAndVipLevel(player.getId(), 3));
    }

    @Test
    void rechargeWithoutPromotionAndInvalidRechargeDoNotAnnounce() {
        Player player = createTestPlayer("vip-promotion-unchanged", 30);
        long previous = messages.count();

        assertEquals(false, vip.onRechargeSuccess(player.getId(), 100L).get("levelUp"));
        assertEquals(previous, messages.count());
        vip.onRechargeSuccess(player.getId(), 500L);
        assertEquals(previous + 1, messages.count());
        assertEquals(false, vip.onRechargeSuccess(player.getId(), 600L).get("levelUp"));
        assertEquals(previous + 1, messages.count());
        assertThrows(IllegalArgumentException.class, () -> vip.onRechargeSuccess(player.getId(), 0L));
        assertEquals(previous + 1, messages.count());
    }

    @Test
    void rechargeAtMaximumLevelDoesNotRepeatPromotionAnnouncement() {
        Player player = createTestPlayer("vip-promotion-max", 30);
        vip.onRechargeSuccess(player.getId(), 1000000L);
        long previous = messages.count();

        assertEquals(false, vip.onRechargeSuccess(player.getId(), 600L).get("levelUp"));

        assertEquals(previous, messages.count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollbackRemovesAnnouncementAndOnlyCommittedPromotionBroadcasts() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        Long playerId = transaction.execute(status -> createTestPlayer("vip-promotion-transaction", 30).getId());
        long previous = messages.count();
        clearInvocations(sockets);

        transaction.executeWithoutResult(status -> {
            vip.onRechargeSuccess(playerId, 600L);
            assertEquals(previous + 1, messages.count());
            verify(sockets, never()).broadcast(anyString());
            status.setRollbackOnly();
        });
        assertEquals(previous, messages.count());
        assertEquals(0, playerRepository.findById(playerId).orElseThrow().getVipLevel());
        assertEquals(0L, playerRepository.findById(playerId).orElseThrow().getTotalRechargeCents());
        verify(sockets, never()).broadcast(anyString());

        transaction.executeWithoutResult(status -> {
            vip.onRechargeSuccess(playerId, 600L);
            verify(sockets, never()).broadcast(anyString());
        });
        ChatMessage message = onlyNewMessage(previous);
        ArgumentCaptor<String> broadcast = ArgumentCaptor.forClass(String.class);
        verify(sockets, times(1)).broadcast(broadcast.capture());
        var payload = new ObjectMapper().readTree(broadcast.getValue());
        assertEquals("chat", payload.path("type").asText());
        assertEquals("system", payload.path("data").path("type").asText());
        assertEquals(message.getId(), payload.path("data").path("id").asLong());
        assertEquals(message.getContent(), payload.path("data").path("content").asText());
        assertEquals(1, playerRepository.findById(playerId).orElseThrow().getVipLevel());
    }

    private ChatMessage onlyNewMessage(long previous) {
        assertEquals(previous + 1, messages.count());
        ChatMessage message = messages.findAll().stream().max(java.util.Comparator.comparing(ChatMessage::getId)).orElseThrow();
        assertNull(message.getPlayerId());
        assertEquals("系统", message.getUsername());
        return message;
    }
}
