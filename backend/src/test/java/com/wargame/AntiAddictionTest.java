package com.wargame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.config.*;
import com.wargame.model.compliance.ComplianceRecords.*;
import com.wargame.model.entity.Player;
import com.wargame.security.GameAccessException;
import com.wargame.service.AuthService;
import com.wargame.service.compliance.*;
import com.wargame.util.JwtUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@SpringBootTest(properties = {"game.compliance.enabled=true", "game.compliance.local-fixtures=true"})
class AntiAddictionTest extends BaseServiceTest {
    static class MutableClock extends Clock {
        volatile Instant time = Instant.parse("2026-09-18T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(time, zone); }
        public Instant instant() { return time; }
        void set(String value) { time = Instant.parse(value); }
        void advance(long seconds) { time = time.plusSeconds(seconds); }
    }
    @TestConfiguration static class TimeConfig { @Bean @Primary MutableClock testClock() { return new MutableClock(); } }
    @Autowired MutableClock clock;
    @Autowired AntiAddictionService protection;
    @Autowired PlayCalendar calendar;
    @Autowired ComplianceProperties config;
    @Autowired EntityManager em;
    @Autowired IdentityVault vault;
    @Autowired AuthService auth;
    @Autowired JwtUtil jwt;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;

    private static String session() { return UUID.randomUUID().toString().replace("-", ""); }
    private String token(Player player) { return jwt.generateToken(player.getUsername(), player.getId(), player.getAuthVersion()); }
    private Player verified(String name, String fixture) {
        Player player = createTestPlayer(name, 30); protection.verify(player, fixture); return player;
    }
    private Subject subject(Player player) { return em.find(Subject.class, em.find(Binding.class, player.getId()).getSubjectId()); }
    private void denied(String code, Runnable operation) { assertEquals(code, assertThrows(GameAccessException.class, operation::run).getCode()); }
    @BeforeEach void reset() {
        clock.set("2026-09-18T12:00:00Z"); config.setLocalFixtures(true);
        config.setExtraOpenDates(new ArrayList<>()); config.setClosedDates(new ArrayList<>());
        config.setCalendarFrom(""); config.setCalendarThrough(""); config.setCalendarSource("");
    }

    @Test void unverifiedAccountsCannotReadWriteOrDismissTutorialButCanUseAccountServices() throws Exception {
        Player player = createTestPlayer("unverified-access", 30);
        String bearer = "Bearer " + token(player);
        for (String path : List.of("/api/game/state", "/api/game/world/map/target?kind=player&id=1", "/api/game/chat/history", "/api/game/onboarding", "/api/game/reports"))
            http.perform(get(path).header("Authorization", bearer)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REAL_NAME_REQUIRED"));
        http.perform(post("/api/auth/tutorial/dismiss").header("Authorization", bearer)).andExpect(status().isForbidden());
        http.perform(post("/api/game/build/upgrade").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"house\"}"))
                .andExpect(status().isForbidden());
        http.perform(get("/api/anti-addiction/status").header("Authorization", bearer)).andExpect(status().isOk()).andExpect(jsonPath("$.identityStatus").value("UNVERIFIED"));
        http.perform(get("/api/auth/deletion-preview").header("Authorization", bearer)).andExpect(status().isOk());
        http.perform(post("/api/auth/guest")).andExpect(status().isForbidden());
    }

    @Test void disabledProtectionInitializesPreviouslyBlockedAccountsAndAllowsGameplay() throws Exception {
        createTestWorld();
        var result = auth.register("paused-protection", "password123", new MockHttpServletRequest());
        Player player = playerRepository.findById(result.playerId()).orElseThrow();
        assertFalse(player.isGameInitialized());
        config.setEnabled(false);
        try {
            String bearer = "Bearer " + token(player);
            http.perform(post("/api/play-sessions").header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"sessionSecret\":\"\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                    .andExpect(jsonPath("$.canPlay").value(true));
            assertTrue(playerRepository.findById(player.getId()).orElseThrow().isGameInitialized());
            http.perform(get("/api/game/state").header("Authorization", bearer)).andExpect(status().isOk());
            http.perform(post("/api/auth/tutorial/dismiss").header("Authorization", bearer)).andExpect(status().isOk());
        } finally {
            config.setEnabled(true);
        }
    }

    @Test void registrationDoesNotCreateWorldAssetsOrGetRepairedOnRestart() {
        var result = auth.register("waiting-registration", "password123", new MockHttpServletRequest());
        Player player = playerRepository.findById(result.playerId()).orElseThrow();
        assertFalse(player.isGameInitialized()); assertNull(player.getCityPosX());
        tickService.tick(player.getId()); gameStateService.repairExistingPlayerCoordinates();
        assertTrue(resourcesRepository.findByPlayerId(player.getId()).isEmpty());
        assertTrue(playerCityRepository.findByOwnerId(player.getId()).isEmpty());
    }

    @Test void timeWindowIsBeijingHalfOpenAndLateLoginNeverGetsAnExtraHour() {
        Player player = verified("window-player", "DEMO-TEEN");
        clock.set("2026-09-18T11:59:59Z");
        denied("PLAY_WINDOW_CLOSED", () -> protection.start(player, session()));
        clock.set("2026-09-18T12:40:00Z");
        String secret = session(); var status = protection.start(player, secret);
        assertEquals(1200L, status.get("remainingSeconds"));
        assertEquals(Instant.parse("2026-09-18T13:00:00Z").toEpochMilli(), status.get("allowedUntil"));
        clock.set("2026-09-18T12:59:30Z");
        String late = session(); protection.start(player, late);
        clock.set("2026-09-18T12:59:59Z"); protection.requireAccess(player, late, "/api/game/state");
        clock.set("2026-09-18T13:00:00Z"); denied("PLAY_WINDOW_CLOSED", () -> protection.requireAccess(player, late, "/api/game/state"));
    }

    @Test void sameIdentitySharesDailyUsageAndSwitchingAccountRevokesPreviousSession() {
        Player first = verified("shared-one", "DEMO-TEEN"), second = verified("shared-two", "DEMO-TEEN");
        String one = session(), two = session(); protection.start(first, one);
        clock.advance(30); protection.renew(first, one);
        clock.advance(30); protection.start(second, two);
        denied("PLAY_SESSION_EXPIRED", () -> protection.requireAccess(first, one, "/api/game/state"));
        assertEquals(60L, protection.status(second.getId(), two).get("usedSeconds"));
        protection.start(second, two); protection.renew(second, two);
        assertEquals(60L, protection.status(second.getId(), two).get("usedSeconds"));
        clock.advance(61); denied("PLAY_SESSION_EXPIRED", () -> protection.renew(second, two));
        protection.start(second, session());
        assertEquals(120L, protection.status(second.getId(), null).get("usedSeconds"));
    }

    @Test void guardianCanTightenRulesButAnUnrelatedAdultCannotBindOrOverride() {
        Player child = verified("guardian-child", "DEMO-CHILD"), parent = verified("guardian-parent", "DEMO-PARENT"), other = verified("guardian-other", "DEMO-ADULT");
        assertEquals(1, protection.children(parent.getId()).size());
        denied("GUARDIAN_REQUIRED", () -> protection.restrict(other.getId(), child.getId(), 60, 1260, false, false));
        assertThrows(IllegalArgumentException.class, () -> protection.restrict(parent.getId(), child.getId(), 3601, 1260, false, true));
        protection.restrict(parent.getId(), child.getId(), 60, 1260, false, false);
        String secret = session(); protection.start(child, secret);
        denied("CHAT_RESTRICTED", () -> protection.requireAccess(child, secret, "/api/game/chat/history"));
        protection.requireAccess(child, secret, "/api/game/world/declare-war");
        clock.advance(60); denied("PLAY_TIME_EXHAUSTED", () -> protection.requireAccess(child, secret, "/api/game/state"));
        protection.restrict(parent.getId(), child.getId(), 3600, 1260, true, true);
        denied("GUARDIAN_RESTRICTED", () -> protection.start(child, session()));
    }

    @Test void verifiedAdultsCanPlayMondayAndCannotUseSimulatedRecharge() throws Exception {
        Player player = verified("adult-monday", "DEMO-ADULT");
        clock.set("2026-09-21T04:00:00Z"); subject(player).setVerifiedUntil(clock.millis() + 86_400_000L);
        String secret = session(); protection.start(player, secret);
        protection.requireAccess(player, secret, "/api/game/state");
        http.perform(post("/api/game/shop/recharge").header("Authorization", "Bearer " + token(player)).header("X-Play-Session", secret)
                .contentType(MediaType.APPLICATION_JSON).content("{\"pkgId\":\"p1280\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PAYMENT_DISABLED"));
    }

    @Test void expiredIdentityAndOldAuthVersionCannotUseAValidJwtSession() {
        Player player = verified("expired-player", "DEMO-TEEN"); String secret = session(); protection.start(player, secret);
        player.setAuthVersion(player.getAuthVersion() + 1);
        denied("PLAY_SESSION_EXPIRED", () -> protection.requireAccess(player, secret, "/api/game/state"));
        subject(player).setVerifiedUntil(clock.millis());
        denied("REAL_NAME_REQUIRED", () -> protection.start(player, session()));
    }

    @Test void ageEighteenChangesRestrictionsUsingVerifiedBirthday() {
        Player player = verified("birthday-player", "DEMO-TEEN");
        subject(player).setBirthCipher(vault.encrypt("2008-09-18"));
        clock.set("2026-09-18T01:00:00Z");
        assertEquals(false, protection.status(player.getId(), null).get("minor"));
        protection.start(player, session());
        subject(player).setBirthCipher(vault.encrypt("2008-09-19"));
        denied("PLAY_WINDOW_CLOSED", () -> protection.start(player, session()));
    }

    @Test void calendarExceptionsDoNotTreatVacationAsDailyPermissionAndMissingCoverageFailsClosed() {
        config.setLocalFixtures(false); config.setCalendarFrom("2026-09-01"); config.setCalendarThrough("2026-10-31"); config.setCalendarSource("test-reviewed-calendar");
        assertFalse(calendar.open(LocalDate.parse("2026-09-21")));
        config.setExtraOpenDates(List.of("2026-09-21")); assertTrue(calendar.open(LocalDate.parse("2026-09-21")));
        config.setClosedDates(List.of("2026-09-18")); assertFalse(calendar.open(LocalDate.parse("2026-09-18")));
        assertFalse(calendar.covered(LocalDate.parse("2026-11-01")));
        assertEquals(0, calendar.nextWindow(Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()));
    }

    @Test void rawProofBirthAndSubjectAreAbsentFromPublicStatusAndWrongIdentityCannotReplaceBinding() throws Exception {
        Player player = verified("private-player", "DEMO-TEEN");
        String status = json.writeValueAsString(protection.status(player.getId(), null));
        assertFalse(status.contains("2010-01-01")); assertFalse(status.contains(subject(player).getId())); assertFalse(status.contains("DEMO-TEEN"));
        assertFalse(subject(player).getBirthCipher().contains("2010"));
        denied("IDENTITY_CHANGE_REQUIRES_REVIEW", () -> protection.verify(player, "DEMO-ADULT"));
    }

    @Test void accountDeletionKeepsSameDayUsageThenPurgesUnboundIdentity() {
        Player player = verified("deleted-compliance", "DEMO-TEEN"); String id = subject(player).getId();
        String secret = session(); protection.start(player, secret); clock.advance(20);
        protection.deleteAccount(player.getId());
        assertNull(em.find(Binding.class, player.getId())); assertNotNull(em.find(Subject.class, id));
        Player another = verified("new-compliance", "DEMO-TEEN");
        assertEquals(20L, protection.status(another.getId(), null).get("usedSeconds"));
        protection.deleteAccount(another.getId());
        clock.set("2026-09-19T00:00:00Z"); protection.maintain(); em.flush();
        assertNull(em.find(Subject.class, id));
    }

    @Test void helpRequestIsIdempotentAndAvailableOutsidePlayWindow() throws Exception {
        Player player = verified("help-player", "DEMO-TEEN"); clock.set("2026-09-18T14:00:00Z");
        String id = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) http.perform(post("/api/protection/requests").header("Authorization", "Bearer " + token(player))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("kind", "IDENTITY", "requestId", id))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertEquals(1, protection.requests(player.getId()).size());
    }

    @Test void logoutEndsTheServerLeaseImmediately() throws Exception {
        Player player = verified("logout-compliance", "DEMO-TEEN"); String secret = session(); protection.start(player, secret);
        clock.advance(15);
        http.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token(player)).header("X-Play-Session", secret))
                .andExpect(status().isOk());
        denied("PLAY_SESSION_EXPIRED", () -> protection.requireAccess(player, secret, "/api/game/state"));
        assertEquals(15L, protection.status(player.getId(), null).get("usedSeconds"));
    }

    @Test void existingWarfareAndOfflineAssetsAreNotRolledBackAtDeadline() {
        Player player = verified("war-player", "DEMO-TEEN");
        player.setWarAt(clock.millis()); player.setWarEndAt(clock.millis() + 7_200_000L); player.setWarAgainstId(123L);
        String secret = session(); protection.start(player, secret);
        clock.set("2026-09-18T13:00:00Z"); protection.maintain();
        assertEquals(123L, player.getWarAgainstId()); assertEquals(Instant.parse("2026-09-18T14:00:00Z").toEpochMilli(), player.getWarEndAt());
        assertEquals(1000, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getFood());
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousAccountsCannotOwnTwoActiveSessionsForOneIdentity() throws Exception {
        Player first = verified("parallel-first", "DEMO-TEEN"), second = verified("parallel-second", "DEMO-TEEN");
        String one = session(), two = session(); CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = executor.submit(() -> { ready.countDown(); try { go.await(); protection.start(first, one); } catch (InterruptedException e) { throw new RuntimeException(e); } });
            Future<?> b = executor.submit(() -> { ready.countDown(); try { go.await(); protection.start(second, two); } catch (InterruptedException e) { throw new RuntimeException(e); } });
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown(); a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
            int active = 0;
            if (Boolean.TRUE.equals(protection.status(first.getId(), one).get("sessionActive"))) active++;
            if (Boolean.TRUE.equals(protection.status(second.getId(), two).get("sessionActive"))) active++;
            assertEquals(1, active);
        } finally {
            executor.shutdownNow();
            new TransactionTemplate(transactions).executeWithoutResult(s -> {
                protection.deleteAccount(first.getId()); protection.deleteAccount(second.getId());
                clock.advance(86_400); protection.maintain();
            });
        }
    }
}
