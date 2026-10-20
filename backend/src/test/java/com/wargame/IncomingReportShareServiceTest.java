package com.wargame;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.dto.ChatDtos;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IncomingReportShareServiceTest {
    private final SharedIncomingReportRepository shares = mock(SharedIncomingReportRepository.class);
    private final GuildMemberRepository members = mock(GuildMemberRepository.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final WorldViewService views = mock(WorldViewService.class);
    private final GuildChatService chat = mock(GuildChatService.class);
    private final ReportPrivacyService privacy = mock(ReportPrivacyService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final IncomingReportShareService service = new IncomingReportShareService(shares, members, players, views, chat, privacy, mapper);
    private final Player defender = new Player();
    private SharedIncomingReport saved;

    @BeforeEach
    void setup() {
        defender.setId(1L);
        defender.setUsername("守城者");
        when(players.findById(1L)).thenReturn(Optional.of(defender));
        when(members.findByPlayerId(1L)).thenReturn(Optional.of(member(1L, 88L)));
        when(shares.save(any())).thenAnswer(call -> saved = call.getArgument(0));
    }

    @Test
    void sharesTheExactAuthorizedProjectionAndReturnsThePersistedChatMessage() throws Exception {
        var incoming = new LinkedHashMap<String, Object>();
        incoming.put("id", "march-37");
        incoming.put("radarLevel", 4);
        incoming.put("attackerName", "来袭者");
        incoming.put("strengthBand", "101—500");
        incoming.put("arriveAt", 123456);
        when(views.getIncoming(defender)).thenReturn(List.of(incoming));
        var response = new ChatDtos.GuildMessageResponse(9L, 88L, 1L, "守城者", "member", "sent", 123L, null, "player");
        when(chat.send(eq(1L), anyString())).thenReturn(response);

        assertSame(response, service.share(1L, "march-37"));
        assertEquals(1L, saved.getOwnerId());
        assertEquals(88L, saved.getGuildId());
        assertEquals(saved.getToken(), UUID.fromString(saved.getToken()).toString());
        var snapshot = mapper.readValue(saved.getData(), new TypeReference<Map<String, Object>>() {});
        assertEquals(incoming, snapshot.get("incoming"));
        assertEquals("守城者", snapshot.get("ownerName"));
        assertInstanceOf(Number.class, snapshot.get("sharedAt"));
        verify(chat).send(1L, "[军情:" + saved.getToken() + "]");
        assertTrue(("[军情:" + saved.getToken() + "]").length() <= GuildChatService.MAX_LENGTH);
    }

    @Test
    void rejectsNonMembersOtherPlayersIncomingAndExpiredIncomingWithoutSending() {
        when(members.findByPlayerId(2L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.share(2L, "march-37"));
        verifyNoInteractions(views, shares, chat);

        when(views.getIncoming(defender)).thenReturn(List.of(Map.of("id", "march-38")));
        assertThrows(IllegalArgumentException.class, () -> service.share(1L, "march-37"));
        when(views.getIncoming(defender)).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class, () -> service.share(1L, "march-38"));
        verifyNoInteractions(shares, chat);
    }

    @Test
    void supportsLegacyNumericIncomingIdsAndPropagatesChatFailure() {
        when(views.getIncoming(defender)).thenReturn(List.of(Map.of("id", 37L, "radarLevel", 0)));
        when(chat.send(eq(1L), anyString())).thenThrow(new IllegalArgumentException("发言过于频繁"));
        assertEquals("发言过于频繁", assertThrows(IllegalArgumentException.class, () -> service.share(1L, "37")).getMessage());
    }

    @Test
    void onlyOriginalGuildMembersCanReadAndSnapshotSurvivesTheMarch() {
        var snapshot = new SharedIncomingReport("token", 1L, 88L,
                "{\"ownerName\":\"守城者\",\"incoming\":{\"id\":\"march-37\",\"radarLevel\":4},\"sharedAt\":123}");
        when(shares.findById("token")).thenReturn(Optional.of(snapshot));
        when(members.findByPlayerId(2L)).thenReturn(Optional.of(member(2L, 88L)));
        var read = service.read(2L, "token");
        assertEquals(Map.of("id", "march-37", "radarLevel", 4), read.get("incoming"));
        verify(privacy).anonymize(List.of(read));
        verifyNoInteractions(views, players);

        when(members.findByPlayerId(2L)).thenReturn(Optional.of(member(2L, 99L)));
        assertThrows(IllegalArgumentException.class, () -> service.read(2L, "token"));
        when(members.findByPlayerId(2L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.read(2L, "token"));
        assertThrows(IllegalArgumentException.class, () -> service.read(2L, "unknown"));
    }

    private static GuildMember member(Long player, Long guild) {
        var member = new GuildMember();
        member.setPlayerId(player);
        member.setGuildId(guild);
        return member;
    }
}
