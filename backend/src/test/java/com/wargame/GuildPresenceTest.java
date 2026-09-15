package com.wargame;

import com.wargame.config.GameWebSocketHandler;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuildPresenceTest {
    @Test
    @SuppressWarnings("unchecked")
    void ordinaryMemberCanSeeServerPresence() {
        GuildRepository guilds = mock(GuildRepository.class);
        GuildMemberRepository members = mock(GuildMemberRepository.class);
        GuildApplicationRepository applications = mock(GuildApplicationRepository.class);
        PlayerRepository players = mock(PlayerRepository.class);
        GameWebSocketHandler presence = mock(GameWebSocketHandler.class);
        GuildService service = new GuildService(guilds, members, applications, players, mock(MailService.class), presence);
        Guild guild = new Guild();
        guild.setId(4L); guild.setLeaderPlayerId(1L);
        when(guilds.findById(4L)).thenReturn(Optional.of(guild));
        GuildMember leader = new GuildMember(null, 4L, 1L, "leader", 1L);
        GuildMember viewer = new GuildMember(null, 4L, 2L, "member", 2L);
        when(members.findByPlayerId(2L)).thenReturn(Optional.of(viewer));
        when(members.findByGuildIdOrderByJoinedAtAsc(4L)).thenReturn(List.of(leader, viewer));
        for (long id = 1; id <= 2; id++) {
            Player p = new Player(); p.setId(id); p.setUsername("member" + id);
            when(players.findById(id)).thenReturn(Optional.of(p));
        }
        when(presence.isPlayerOnline(1L)).thenReturn(true);
        List<Map<String, Object>> result = (List<Map<String, Object>>) service.getMyGuild(2L).get("members");
        assertEquals(true, result.get(0).get("online"));
        assertEquals(false, result.get(1).get("online"));
        assertFalse(service.getMyGuild(2L).containsKey("applications"));
    }
}
