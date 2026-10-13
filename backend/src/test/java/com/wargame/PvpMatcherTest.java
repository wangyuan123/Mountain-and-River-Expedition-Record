package com.wargame;
import com.wargame.service.pvp.*;
import com.wargame.repository.PvpMatchTicketRepository;
import com.wargame.config.GameServerIdentity;
import com.wargame.model.entity.PvpMatchTicket;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class PvpMatcherTest {
    private PvpMatchTicket ticket(long player,long power,long time){
        var t=new PvpMatchTicket();t.setId("ticket-"+player);t.setPlayerId(player);t.setRegion("r");t.setBattlefield("LAND_AIR");t.setRulesVersion("rules");t.setPowerModelVersion("model");t.setStatus("QUEUED");t.setQueuedAt(time);t.setLeaseUntil(time+30000);t.setPower(power);t.setRating(1000);t.setPlacement(true);return t;
    }
    @Test void randomSelectionUsesOnlyBestFiveEligibleCandidatesAndClaimFailureRereads() {
        var tickets=mock(PvpMatchTicketRepository.class);var practice=mock(PvpPracticeService.class);var region=mock(GameServerIdentity.class);when(region.id()).thenReturn("r");
        long time=System.currentTimeMillis();var anchor=ticket(1,10000,time);List<PvpMatchTicket> candidates=new ArrayList<>();
        for(int i=2;i<=9;i++)candidates.add(ticket(i,10000+(i-2)*100,time));
        var incompatible=ticket(99,10000,time);incompatible.setRegion("other");candidates.add(incompatible);
        when(tickets.findByRegionAndStatusOrderByQueuedAtAsc(eq("r"),eq("QUEUED"),any())).thenReturn(List.of(anchor));
        when(tickets.candidates(anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong(),anyLong(),anyInt(),anyInt(),any())).thenReturn(candidates);
        when(practice.repeatPenalties(1L)).thenReturn(Map.of());
        var choices=new ArrayList<String>();when(practice.pair(eq(anchor.getId()),anyString())).thenAnswer(call->{choices.add(call.getArgument(1));return choices.size()%2==0;});
        var matcher=new PvpMatchService(tickets,practice,region);assertEquals(1,matcher.match());
        assertEquals(2,choices.size());assertTrue(choices.stream().allMatch(id->Set.of("ticket-2","ticket-3","ticket-4","ticket-5","ticket-6").contains(id)));
        verify(tickets,times(2)).candidates(anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong(),anyLong(),anyInt(),anyInt(),any());
    }
}
