package com.wargame.config;

import com.wargame.model.entity.Player;
import com.wargame.repository.PlayerRepository;
import com.wargame.security.GameAccessException;
import com.wargame.security.RateLimiter;
import com.wargame.service.compliance.*;
import com.wargame.util.JwtUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComplianceBoundaryTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC);

    @Test void unavailableOfficialProviderNeverAcceptsFixtureCodes() {
        RealNameProvider provider = new ComplianceConfig().realNameProvider(new ComplianceProperties(), clock);
        assertFalse(provider.available());
        assertEquals("IDENTITY_UNAVAILABLE", assertThrows(GameAccessException.class, () -> provider.verify("DEMO-ADULT", 1L)).getCode());
    }

    @Test void productionStillRejectsFixturesWhileEnforcementIsTemporarilyDisabled() {
        ComplianceProperties config = new ComplianceProperties();
        MockEnvironment env = new MockEnvironment(); env.setActiveProfiles("prod", "test", "local");
        var check = new ComplianceConfig().complianceStartupCheck(config, env, new IdentityVault(config));
        config.setLocalFixtures(true);
        assertThrows(IllegalStateException.class, check::afterPropertiesSet);
        config.setLocalFixtures(false); config.setEnabled(false);
        assertDoesNotThrow(check::afterPropertiesSet);
    }

    @Test void incompleteCalendarCannotSilentlyClaimCoverage() {
        ComplianceProperties config = new ComplianceProperties(); config.setCalendarFrom("2026-09-01");
        var check = new ComplianceConfig().complianceStartupCheck(config, new MockEnvironment(), new IdentityVault(config));
        assertThrows(IllegalStateException.class, check::afterPropertiesSet);
    }

    @Test void childVerificationWithoutTrustedGuardianConsentDoesNotPersistIdentity() {
        ComplianceProperties config = new ComplianceProperties(); config.setDataKey(Base64.getEncoder().encodeToString(new byte[32]));
        EntityManager em = mock(EntityManager.class);
        RealNameProvider provider = mock(RealNameProvider.class);
        when(provider.verify("opaque-proof", 1L)).thenReturn(new RealNameProvider.VerifiedIdentity("child", LocalDate.of(2016, 1, 1), clock.millis() + 60000, null, null));
        RateLimiter limiter = mock(RateLimiter.class); when(limiter.allow(anyString(), anyInt(), anyLong())).thenReturn(true);
        AntiAddictionService service = new AntiAddictionService(em, mock(PlayerRepository.class), config, new IdentityVault(config), provider, new PlayCalendar(config, clock), clock, limiter);
        Player player = new Player(); player.setId(1L);
        assertEquals("GUARDIAN_CONSENT_REQUIRED", assertThrows(GameAccessException.class, () -> service.verify(player, "opaque-proof")).getCode());
        verify(em, never()).persist(any());
    }

    @Test void revokedWebSocketStopsPayloadsAndClosesWithGameCode() throws Exception {
        Fixture f = new Fixture(); f.handler.afterConnectionEstablished(f.session);
        doThrow(new GameAccessException("PLAY_WINDOW_CLOSED", "休息时间")).when(f.protection).requireAccess(f.player, "session", "/ws/game");
        f.handler.sendToPlayer(7L, "{\"type\":\"tick\"}");
        verify(f.session, never()).sendMessage(any());
        verify(f.session).close(argThat(status -> status.getCode() == 4003));
        assertEquals(0, f.handler.getSessionCount(7L));
    }

    @Test void guardianChatRestrictionDropsBroadcastWithoutStoppingPermittedGameplay() throws Exception {
        Fixture f = new Fixture(); f.handler.afterConnectionEstablished(f.session);
        doThrow(new GameAccessException("CHAT_RESTRICTED", "聊天已关闭")).when(f.protection).requireAccess(f.player, "session", "/api/game/chat/push");
        f.handler.broadcast("{\"type\":\"chat\",\"data\":\"private\"}");
        f.handler.sendToPlayer(7L, "{\"type\":\"tick\"}");
        verify(f.session, times(1)).sendMessage(argThat(message -> message.getPayload().equals("{\"type\":\"tick\"}")));
        verify(f.session, never()).close(any());
    }

    @Test void unavailablePermissionStorageClosesExistingSocket() throws Exception {
        Fixture f = new Fixture(); f.handler.afterConnectionEstablished(f.session);
        doThrow(new IllegalStateException("storage unavailable")).when(f.protection).requireAccess(f.player, "session", "/ws/game");
        f.handler.closeInvalidSessions();
        verify(f.session).close(argThat(status -> status.getCode() == 4003));
    }

    private static class Fixture {
        final GameWebSocketHandler handler = new GameWebSocketHandler();
        final AntiAddictionService protection = mock(AntiAddictionService.class);
        final WebSocketSession session = mock(WebSocketSession.class);
        final Player player = new Player();
        Fixture() {
            player.setId(7L);
            PlayerRepository players = mock(PlayerRepository.class); when(players.findById(7L)).thenReturn(Optional.of(player));
            JwtUtil jwt = mock(JwtUtil.class); when(jwt.validateToken("token")).thenReturn(true); when(jwt.getAuthVersion("token")).thenReturn(player.getAuthVersion());
            ReflectionTestUtils.setField(handler, "players", players); ReflectionTestUtils.setField(handler, "jwt", jwt); ReflectionTestUtils.setField(handler, "protection", protection);
            when(session.getId()).thenReturn("socket"); when(session.isOpen()).thenReturn(true);
            when(session.getAttributes()).thenReturn(new HashMap<>(Map.of("playerId", 7L, "token", "token", "playSession", "session")));
        }
    }
}
