package com.wargame;

import com.wargame.config.GameWebSocketHandler;
import com.wargame.model.entity.Guild;
import com.wargame.model.entity.GuildMember;
import com.wargame.model.entity.Player;
import com.wargame.repository.GuildApplicationRepository;
import com.wargame.repository.GuildMemberRepository;
import com.wargame.repository.GuildRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.service.GuildService;
import com.wargame.service.MailService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuildIconTest {
    private final GuildRepository guilds = mock(GuildRepository.class);
    private final GuildMemberRepository members = mock(GuildMemberRepository.class);
    private final GuildApplicationRepository applications = mock(GuildApplicationRepository.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final GuildService service = new GuildService(guilds, members, applications, players,
            mock(MailService.class), mock(GameWebSocketHandler.class));

    @Test
    void selectedEmblemIsSavedDuringCreation() {
        AtomicReference<Guild> saved = new AtomicReference<>();
        GuildMember leader = new GuildMember(null, 12L, 7L, "leader", 1L);
        Player player = new Player();
        player.setId(7L);
        player.setUsername("leader");
        when(members.findByPlayerId(7L)).thenReturn(Optional.empty(), Optional.of(leader));
        when(guilds.save(any(Guild.class))).thenAnswer(invocation -> {
            Guild guild = invocation.getArgument(0);
            guild.setId(12L);
            saved.set(guild);
            return guild;
        });
        when(guilds.findById(12L)).thenAnswer(invocation -> Optional.of(saved.get()));
        when(players.findById(7L)).thenReturn(Optional.of(player));
        when(members.findByGuildIdOrderByJoinedAtAsc(12L)).thenReturn(List.of(leader));
        when(applications.findByGuildIdOrderByCreatedAtAsc(12L)).thenReturn(List.of());

        assertEquals("g04", service.create(7L, "海卫", "g04").get("icon"));
        assertEquals("g04", saved.get().getIcon());
    }

    @Test
    void invalidIconIsRejectedBeforeSaving() {
        assertThrows(IllegalArgumentException.class, () -> service.create(7L, "战舞", ""));
        assertThrows(IllegalArgumentException.class, () -> service.create(7L, "战舞", "12345"));
    }
}
