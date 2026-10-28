package com.wargame;

import com.wargame.model.dto.ChatDtos;
import com.wargame.model.entity.GuildChatMessage;
import com.wargame.model.entity.GuildMember;
import com.wargame.model.entity.Player;
import com.wargame.repository.GuildChatMessageRepository;
import com.wargame.repository.GuildMemberRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.security.RateLimiter;
import com.wargame.service.ChatKeywordFilter;
import com.wargame.service.GuildChatService;
import com.wargame.service.WebSocketPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public class GuildChatServiceTest {

    private GuildChatService guildChatService;
    private GuildChatMessageRepository chatRepo;
    private GuildMemberRepository memberRepo;
    private PlayerRepository playerRepo;
    private Player player;
    private GuildMember member;

    @BeforeEach
    public void setUp() {
        chatRepo = Mockito.mock(GuildChatMessageRepository.class);
        memberRepo = Mockito.mock(GuildMemberRepository.class);
        playerRepo = Mockito.mock(PlayerRepository.class);
        RateLimiter rateLimiter = new RateLimiter();
        WebSocketPushService pushService = Mockito.mock(WebSocketPushService.class);
        ChatKeywordFilter keywordFilter = new ChatKeywordFilter();

        guildChatService = new GuildChatService(chatRepo, memberRepo, playerRepo, rateLimiter, pushService, keywordFilter);

        player = new Player();
        player.setId(100L);
        player.setUsername("雨天唐纳德");
        player.setAvatar("avatar1.png");
        player.setVipLevel(4);
        player.setVipBadgeVisible(true);
        player.setAvatarFrame("frame-gold-v1");

        member = new GuildMember(1L, 10L, 100L, "leader", System.currentTimeMillis());

        when(playerRepo.findById(100L)).thenReturn(Optional.of(player));
        when(memberRepo.findByPlayerId(100L)).thenReturn(Optional.of(member));
        when(memberRepo.findByGuildIdOrderByJoinedAtAsc(10L)).thenReturn(List.of(member));

        when(chatRepo.save(any(GuildChatMessage.class))).thenAnswer(inv -> {
            GuildChatMessage m = inv.getArgument(0);
            return new GuildChatMessage(1L, m.getGuildId(), m.getPlayerId(), m.getUsername(), m.getContent(), m.getCreatedAt());
        });
    }

    @Test
    public void testSendIncludesAvatarFrameAndVip() {
        ChatDtos.GuildMessageResponse res = guildChatService.send(100L, "军团准备出击！");
        assertNotNull(res);
        assertEquals("军团准备出击！", res.content());
        assertEquals("雨天唐纳德", res.username());
        assertEquals("leader", res.role());
        assertEquals(4, res.vipLevel());
        assertTrue(res.vipBadgeVisible());
        assertEquals("frame-gold-v1", res.avatarFrame());
    }

    @Test
    public void testHistoryIncludesAvatarFrameAndVip() {
        GuildChatMessage message = new GuildChatMessage(2L, 10L, 100L, "雨天唐纳德", "历史作战指令", System.currentTimeMillis());
        when(chatRepo.findRecentVisible(eq(10L), any(Long.class), any(Pageable.class)))
                .thenReturn(List.of(message));
        when(playerRepo.findAllById(List.of(100L))).thenReturn(List.of(player));

        List<ChatDtos.GuildMessageResponse> history = guildChatService.history(100L);
        assertNotNull(history);
        assertEquals(1, history.size());
        ChatDtos.GuildMessageResponse item = history.get(0);
        assertEquals(2L, item.id());
        assertEquals(4, item.vipLevel());
        assertTrue(item.vipBadgeVisible());
        assertEquals("frame-gold-v1", item.avatarFrame());
        assertEquals("leader", item.role());
    }
}
