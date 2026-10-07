package com.wargame;

import com.wargame.model.dto.ChatDtos;
import com.wargame.model.entity.Guild;
import com.wargame.model.entity.GuildChatMessage;
import com.wargame.model.entity.GuildMember;
import com.wargame.model.entity.GuildRelation;
import com.wargame.model.entity.Player;
import com.wargame.repository.GuildChatMessageRepository;
import com.wargame.repository.GuildMemberRepository;
import com.wargame.repository.GuildRelationRepository;
import com.wargame.repository.GuildRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.security.RateLimiter;
import com.wargame.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GuildRelationBroadcastTest {

    @Test
    void updatingRelationToHostileBroadcastsToWorldAndBothGuilds() {
        GuildRepository guilds = mock(GuildRepository.class);
        GuildMemberRepository members = mock(GuildMemberRepository.class);
        GuildRelationRepository relations = mock(GuildRelationRepository.class);
        ChatService chatService = mock(ChatService.class);
        GuildChatService guildChatService = mock(GuildChatService.class);

        GuildRelationService service = new GuildRelationService(guilds, members, relations);
        service.setChatService(chatService);
        service.setGuildChatService(guildChatService);

        GuildMember myMember = new GuildMember(1L, 10L, 100L, "leader", 1000L);
        when(members.findByPlayerId(100L)).thenReturn(Optional.of(myMember));

        Guild myGuild = new Guild();
        myGuild.setId(10L);
        myGuild.setName("铁血第一旅");
        when(guilds.findById(10L)).thenReturn(Optional.of(myGuild));

        Guild targetGuild = new Guild();
        targetGuild.setId(20L);
        targetGuild.setName("狂龙军团");
        when(guilds.findById(20L)).thenReturn(Optional.of(targetGuild));

        when(relations.findByGuildLowIdAndGuildHighId(10L, 20L)).thenReturn(Optional.empty());

        service.updateRelation(100L, 20L, "hostile");

        // 1. 验证保存了敌对关系
        ArgumentCaptor<GuildRelation> relCaptor = ArgumentCaptor.forClass(GuildRelation.class);
        verify(relations).save(relCaptor.capture());
        assertEquals("hostile", relCaptor.getValue().getStatus());

        // 2. 验证推送到世界频道
        ArgumentCaptor<String> worldCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatService).sendSystem(worldCaptor.capture());
        assertTrue(worldCaptor.getValue().contains("铁血第一旅"));
        assertTrue(worldCaptor.getValue().contains("狂龙军团"));
        assertTrue(worldCaptor.getValue().contains("敌对阵营"));

        // 3. 验证推送到双方军团频道
        verify(guildChatService).sendSystem(eq(10L), contains("全军进入战备状态"));
        verify(guildChatService).sendSystem(eq(20L), contains("请全体成员提高警惕"));
    }

    @Test
    void guildChatServiceSendAndHistory() {
        GuildChatMessageRepository chatRepo = mock(GuildChatMessageRepository.class);
        GuildMemberRepository memberRepo = mock(GuildMemberRepository.class);
        PlayerRepository playerRepo = mock(PlayerRepository.class);
        RateLimiter rateLimiter = mock(RateLimiter.class);
        WebSocketPushService pushService = mock(WebSocketPushService.class);
        ChatKeywordFilter filter = new ChatKeywordFilter();

        GuildChatService chatService = new GuildChatService(
                chatRepo, memberRepo, playerRepo, rateLimiter, pushService, filter
        );

        when(rateLimiter.allow(anyString(), anyInt(), anyLong())).thenReturn(true);

        GuildMember member = new GuildMember(1L, 10L, 100L, "admin", 1000L);
        when(memberRepo.findByPlayerId(100L)).thenReturn(Optional.of(member));
        when(memberRepo.findByGuildIdOrderByJoinedAtAsc(10L)).thenReturn(List.of(member));

        Player player = new Player();
        player.setId(100L);
        player.setUsername("指挥官赵");
        player.setAvatar("avatar1.png");
        when(playerRepo.findById(100L)).thenReturn(Optional.of(player));
        when(playerRepo.findAllById(any())).thenReturn(List.of(player));

        when(chatRepo.save(any(GuildChatMessage.class))).thenAnswer(inv -> {
            GuildChatMessage m = inv.getArgument(0);
            m.setId(999L);
            return m;
        });

        // 1. 发送消息
        ChatDtos.GuildMessageResponse sent = chatService.send(100L, "今晚八点集中进攻！");
        assertEquals("指挥官赵", sent.username());
        assertEquals("admin", sent.role());
        assertEquals("今晚八点集中进攻！", sent.content());
        verify(pushService).pushToPlayer(eq(100L), eq("guild_chat"), any());

        // 2. 拉取历史
        GuildChatMessage historyMsg = new GuildChatMessage(999L, 10L, 100L, "指挥官赵", "今晚八点集中进攻！", 2000L);
        when(chatRepo.findTop50ByGuildIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(historyMsg));

        List<ChatDtos.GuildMessageResponse> history = chatService.history(100L);
        assertEquals(1, history.size());
        assertEquals("admin", history.get(0).role());
        assertEquals("avatar1.png", history.get(0).avatar());

        // 入库与旧历史均使用共用词库，军团聊天没有独立的漏过滤路径。
        var filtered = chatService.send(100L, "q_q卖号");
        assertEquals("*_***", filtered.content());
        verify(chatRepo).save(argThat(m -> m.getContent().equals("*_***")));
        when(chatRepo.findTop50ByGuildIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(
                new GuildChatMessage(1000L, 10L, 100L, "指挥官赵", "V.X代充", 3000L)));
        assertEquals("*.***", chatService.history(100L).get(0).content());
    }
}
