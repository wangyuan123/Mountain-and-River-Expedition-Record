package com.wargame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportShareServiceTest {
    @Test void shareChecksOwnershipAndRoutesToRequestedChat() {
        var reports = mock(ScoutReportRepository.class);
        var shares = mock(SharedReportRepository.class);
        var world = mock(ChatService.class);
        var guild = mock(GuildChatService.class);
        var direct = mock(PrivateChatService.class);
        var service = new ReportShareService(reports, shares, world, guild, direct, mock(ReportPrivacyService.class), new ObjectMapper());
        var report = new ScoutReport(); report.setId(3L); report.setPlayerId(1L); report.setType("battle");
        when(reports.findById(3L)).thenReturn(Optional.of(report));
        assertThrows(IllegalArgumentException.class, () -> service.share(2L, 3L, "world", null));
        verifyNoInteractions(shares, world, guild, direct);
        service.share(1L, 3L, "world", null);
        verify(world).send(eq(1L), matches("\\[战报:[0-9a-f-]{36}\\]"));
        service.share(1L, 3L, "guild", null);
        verify(guild).send(eq(1L), anyString());
        service.share(1L, 3L, "private", 2L);
        verify(direct).send(eq(1L), eq(2L), anyString());
    }
    @Test void unknownTokensCannotReadPrivateReports() {
        var shares = mock(SharedReportRepository.class);
        var reports = mock(ScoutReportRepository.class);
        var service = new ReportShareService(reports, shares, mock(ChatService.class), mock(GuildChatService.class), mock(PrivateChatService.class), mock(ReportPrivacyService.class), new ObjectMapper());
        assertThrows(IllegalArgumentException.class, () -> service.read("unknown"));
        verifyNoInteractions(reports);
    }
}
