package com.wargame;

import com.wargame.model.dto.ChatDtos;
import com.wargame.model.entity.ChatMessage;
import com.wargame.model.entity.Player;
import com.wargame.repository.ChatMessageRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.security.RateLimiter;
import com.wargame.service.ChatService;
import com.wargame.service.PoliticalWordFilter;
import com.wargame.service.WebSocketPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class ChatServiceTest {

    private ChatService chatService;
    private RateLimiter rateLimiter;
    private Player player;
    private ChatMessageRepository chatRepo;

    @BeforeEach
    public void setUp() {
        chatRepo = Mockito.mock(ChatMessageRepository.class);
        PlayerRepository playerRepo = Mockito.mock(PlayerRepository.class);
        WebSocketPushService pushService = Mockito.mock(WebSocketPushService.class);
        rateLimiter = new RateLimiter();
        chatService = new ChatService(chatRepo, playerRepo, rateLimiter, pushService, new PoliticalWordFilter());

        player = new Player();
        player.setId(999L);
        player.setUsername("TestCommander");
        player.setPrestige(10000);
        when(playerRepo.findById(999L)).thenReturn(Optional.of(player));
        when(chatRepo.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            return new ChatMessage(1L, m.getPlayerId(), m.getUsername(), m.getContent(), m.getCreatedAt());
        });
    }

    @Test
    public void testSendSuccess() {
        ChatDtos.MessageResponse res = chatService.send(player.getId(), "指挥部全员就绪！");
        assertNotNull(res);
        assertEquals("指挥部全员就绪！", res.content());
        assertEquals("TestCommander", res.username());
    }

    @Test
    public void testPrestigeBelowThresholdCannotSend() {
        player.setPrestige(9999);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> chatService.send(player.getId(), "新手发言"));
        assertTrue(ex.getMessage().contains("声望达到 10000"));
        Mockito.verify(chatRepo, Mockito.never()).save(any(ChatMessage.class));

        // 被拒绝的发言不应占用次数或冷却时间。
        player.setPrestige(10000);
        assertNotNull(chatService.send(player.getId(), "达标发言"));
    }

    @Test
    public void testNullPrestigeCannotSend() {
        player.setPrestige(null);
        assertThrows(IllegalArgumentException.class,
                () -> chatService.send(player.getId(), "新手发言"));
        Mockito.verify(chatRepo, Mockito.never()).save(any(ChatMessage.class));
    }

    @Test
    public void testSendEmptyMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            chatService.send(player.getId(), "   ");
        });
        assertTrue(ex.getMessage().contains("消息不能为空"));
    }

    @Test
    public void testSendTooLongMessage() {
        String longText = "A".repeat(81);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            chatService.send(player.getId(), longText);
        });
        assertTrue(ex.getMessage().contains("80"));
    }

    @Test
    public void testDuplicateContentSpam() {
        chatService.send(player.getId(), "电报测试内容A");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            chatService.send(player.getId(), "电报测试内容A");
        });
        assertTrue(ex.getMessage().contains("请勿连续发送相同内容刷屏"));
    }

    @Test
    public void testCooldownSpam() {
        chatService.send(player.getId(), "电报第一条");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            chatService.send(player.getId(), "电报第二条");
        });
        assertTrue(ex.getMessage().contains("发言过于频繁"));
        assertTrue(ex.getMessage().contains("秒后再试"));
    }

    @Test
    public void testSensitiveWordsFilter() {
        ChatDtos.MessageResponse res = chatService.send(player.getId(), "管理员不要搞事");
        assertNotNull(res);
        assertTrue(res.content().contains("***"));
    }

    @Test
    public void testPoliticalWordsFilterBeforeSaving() {
        ChatDtos.MessageResponse res = chatService.send(player.getId(), "台-独内容");
        assertEquals("*-*内容", res.content());
    }

    @Test
    public void testPoliticalWordCannotBypassDuplicateLimit() {
        chatService.send(player.getId(), "台-独内容");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> chatService.send(player.getId(), "台-独内容"));
        assertTrue(ex.getMessage().contains("请勿连续发送相同内容"));
    }

    @Test
    public void testBurstFrequencyLimit() {
        Long testPlayerId = 888L;
        String burstKey = "CHAT_BURST:" + testPlayerId;
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.allow(burstKey, 5, 60000L));
        }
        assertFalse(rateLimiter.allow(burstKey, 5, 60000L));
        long waitSec = rateLimiter.retryAfterSeconds(burstKey, 60000L);
        assertTrue(waitSec > 0 && waitSec <= 60);
    }
}
