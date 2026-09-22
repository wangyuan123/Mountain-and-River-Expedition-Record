package com.wargame;

import com.wargame.model.entity.Guild;
import com.wargame.model.entity.GuildMember;
import com.wargame.model.entity.GuildRelation;
import com.wargame.repository.GuildMemberRepository;
import com.wargame.repository.GuildRelationRepository;
import com.wargame.repository.GuildRepository;
import com.wargame.service.GuildRelationService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuildRelationServiceTest {
    @Test
    void managerStoresHostileRelationAsOneSharedGuildPair() {
        GuildRepository guilds = mock(GuildRepository.class);
        GuildMemberRepository members = mock(GuildMemberRepository.class);
        GuildRelationRepository relations = mock(GuildRelationRepository.class);
        GuildRelationService service = new GuildRelationService(guilds, members, relations);
        Guild target = new Guild();
        target.setId(3L);
        when(members.findByPlayerId(99L)).thenReturn(Optional.of(new GuildMember(null, 7L, 99L, "admin", 1L)));
        when(guilds.findById(3L)).thenReturn(Optional.of(target));
        when(relations.findByGuildLowIdAndGuildHighId(3L, 7L)).thenReturn(Optional.empty());

        service.updateRelation(99L, 3L, GuildRelationService.HOSTILE);

        org.mockito.ArgumentCaptor<GuildRelation> saved = org.mockito.ArgumentCaptor.forClass(GuildRelation.class);
        verify(relations).save(saved.capture());
        assertEquals(3L, saved.getValue().getGuildLowId());
        assertEquals(7L, saved.getValue().getGuildHighId());
        assertEquals(GuildRelationService.HOSTILE, saved.getValue().getStatus());
        assertEquals(99L, saved.getValue().getUpdatedByPlayerId());
    }

    @Test
    void membersOfHostileGuildsAreRecognizedAsHostile() {
        GuildRepository guilds = mock(GuildRepository.class);
        GuildMemberRepository members = mock(GuildMemberRepository.class);
        GuildRelationRepository relations = mock(GuildRelationRepository.class);
        GuildRelationService service = new GuildRelationService(guilds, members, relations);
        GuildRelation relation = new GuildRelation();
        relation.setStatus(GuildRelationService.HOSTILE);
        when(members.findByPlayerId(1L)).thenReturn(Optional.of(new GuildMember(null, 3L, 1L, "member", 1L)));
        when(members.findByPlayerId(2L)).thenReturn(Optional.of(new GuildMember(null, 7L, 2L, "member", 1L)));
        when(relations.findByGuildLowIdAndGuildHighId(3L, 7L)).thenReturn(Optional.of(relation));

        assertEquals(true, service.areHostile(1L, 2L));
    }
}
