package com.wargame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.security.AccountException;
import com.wargame.service.*;
import com.wargame.util.JwtUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AccountDeletionTest extends BaseServiceTest {
    @Autowired AccountService accounts;
    @Autowired AuthService auth;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtUtil jwt;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired EntityManager em;
    @Autowired MailRepository mails;
    @Autowired GuildRepository guilds;
    @Autowired GuildMemberRepository members;
    @Autowired GuildService guildService;

    private Player account(String name) {
        Player player = createTestPlayer(name, 30);
        player.setPasswordHash(passwords.encode("test password"));
        return playerRepository.saveAndFlush(player);
    }

    private String token(Player player) { return jwt.generateToken(player.getUsername(), player.getId(), player.getAuthVersion()); }
    private Map<String, Object> disable(Player player) {
        return accounts.disableAccount(player.getId(), "test password", "确认注销", "request-test-123");
    }

    @Test void explicitRecoveryRevokesEveryOldDeviceAndConsumesCredential() throws Exception {
        Player player = account("recover-account");
        String first = token(player), second = token(player);
        Map<String, Object> accepted = disable(player);
        assertEquals("PENDING_DELETION", accepted.get("status"));
        assertEquals(604800000L, (Long) accepted.get("recoverUntil") - (Long) accepted.get("disabledAt"));
        for (String old : List.of(first, second)) {
            http.perform(get("/api/auth/me").header("Authorization", "Bearer " + old)).andExpect(status().isUnauthorized());
        }
        JsonNode login = json.readTree(http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"recover-account\",\"password\":\"test password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("RECOVERY_REQUIRED", login.path("status").asText());
        assertFalse(login.has("token"));
        assertEquals("PENDING_DELETION", player.getAccountStatus());
        String recovery = login.path("recoveryToken").asText();
        assertNotEquals(recovery, player.getRecoveryTokenHash());
        Map<String, Object> restored = accounts.recover(recovery, true);
        assertEquals("ACTIVE", player.getAccountStatus());
        assertNull(player.getRecoveryTokenHash());
        http.perform(get("/api/auth/me").header("Authorization", "Bearer " + restored.get("token"))).andExpect(status().isOk());
        for (String old : List.of(first, second)) {
            http.perform(get("/api/auth/me").header("Authorization", "Bearer " + old)).andExpect(status().isUnauthorized());
        }
        assertThrows(AccountException.class, () -> accounts.recover(recovery, true));
    }

    @Test void wrongPasswordConfirmationGuestAndLeaderCannotDelete() {
        Player player = account("validation-account");
        assertEquals("PASSWORD_INVALID", assertThrows(AccountException.class,
                () -> accounts.disableAccount(player.getId(), "wrong", "确认注销", "request-123")).getCode());
        assertEquals("CONFIRM_REQUIRED", assertThrows(AccountException.class,
                () -> accounts.disableAccount(player.getId(), "test password", "", "request-123")).getCode());
        Player guest = account("游客_987654");
        assertEquals("GUEST_ACCOUNT", assertThrows(AccountException.class, () -> disable(guest)).getCode());
        Guild guild = guilds.save(new Guild(null, "注销校验团", "", "旗", player.getId(), 1L));
        members.save(new GuildMember(null, guild.getId(), player.getId(), "leader", 1L));
        Player member = account("guild-other");
        members.save(new GuildMember(null, guild.getId(), member.getId(), "member", 2L));
        assertEquals("GUILD_TRANSFER_REQUIRED", assertThrows(AccountException.class, () -> disable(player)).getCode());
        assertTrue(player.accountActive());
    }

    @Test void duplicateAndReadOnlyStatusNeverExtendOrCancelDeadline() {
        Player player = account("status-account");
        Map<String, Object> accepted = disable(player);
        assertEquals(accepted.get("recoverUntil"), disable(player).get("recoverUntil"));
        Map<String, Object> status = auth.deletionStatus(player.getUsername(), "test password", new MockHttpServletRequest());
        assertEquals("PENDING_DELETION", status.get("status"));
        assertNull(player.getRecoveryTokenHash());
        assertEquals(1L, player.getAuthVersion());
    }

    @Test void expiredRecoveryCannotWinEvenWhenCleanupHasNotRun() {
        Player player = account("expiry-account");
        disable(player);
        String recovery = (String) auth.login(player.getUsername(), "test password", new MockHttpServletRequest()).get("recoveryToken");
        player.setRecoverUntil(System.currentTimeMillis());
        playerRepository.saveAndFlush(player);
        assertTrue(player.deletionDue(player.getRecoverUntil()));
        assertFalse(player.deletionDue(player.getRecoverUntil() - 1));
        assertEquals("RECOVERY_EXPIRED", assertThrows(AccountException.class, () -> accounts.recover(recovery, true)).getCode());
    }

    @Test void cleanupPreservesOthersMailAndReturningArmyAndNeverRecreatesAssets() {
        WorldMap world = createTestWorld();
        Player player = account("cleanup-account"), other = account("mail-owner");
        PlayerCity city = new PlayerCity();
        city.setWorldId(world.getId()); city.setOwnerId(player.getId()); city.setCitySlot(0);
        city.setName("待清理城"); city.setX(70); city.setY(71);
        city = playerCityRepository.save(city);
        player.setActiveCityId(city.getId());
        createBuilding(player.getId(), "farm", 2);
        createArmyUnit(player.getId(), "infantry", 123);
        WildTile wild = createWildTile(world.getId(), "forest", 80, 80, 1, Map.of("infantry", 10), 1000);
        wild.setOccupied(true); wild.setOccupiedBy(player.getId()); wildTileRepository.save(wild);
        Guild guild = guilds.save(new Guild(null, "单人注销团", "", "旗", player.getId(), 1L));
        members.save(new GuildMember(null, guild.getId(), player.getId(), "leader", 1L));
        Mail received = new Mail(null, player.getId(), player.getUsername(), other.getId(), other.getUsername(),
                "player", false, "历史邮件", "应保留", "[]", false, true, 1L);
        received = mails.save(received);
        March enemy = createMarch(other.getId(), "player", city.getId().toString(), "待清理城", 10, 10, 70, 71,
                Map.of("infantry", 7), "plunder", 1, 1000, false, false);
        disable(player);
        player.setRecoverUntil(System.currentTimeMillis() - 1);
        playerRepository.saveAndFlush(player);
        Long id = player.getId(), mailId = received.getId(), marchId = enemy.getId(), wildId = wild.getId();
        accounts.purgeExpiredAccount(id);
        em.clear();
        assertEquals("DELETED", playerRepository.findById(id).orElseThrow().getAccountStatus());
        assertTrue(playerRepository.existsByUsername("cleanup-account"));
        assertTrue(playerCityRepository.findByOwnerId(id).isEmpty());
        assertTrue(buildingRepository.findByPlayerId(id).isEmpty());
        assertTrue(armyUnitRepository.findByPlayerId(id).isEmpty());
        assertTrue(resourcesRepository.findByPlayerId(id).isEmpty());
        assertEquals("已注销玩家", mails.findById(mailId).orElseThrow().getFromName());
        assertEquals("应保留", mails.findById(mailId).orElseThrow().getBody());
        assertFalse(guilds.existsById(guild.getId()));
        assertNull(wildTileRepository.findById(wildId).orElseThrow().getOccupiedBy());
        marchService.processMarches(other.getId(), System.currentTimeMillis());
        assertTrue(marchRepository.findById(marchId).orElseThrow().getReturning());
        assertEquals("{\"infantry\":7}", marchRepository.findById(marchId).orElseThrow().getArmy());
        gameStateService.repairExistingPlayerCoordinates();
        assertTrue(playerCityRepository.findByOwnerId(id).isEmpty());
        tickService.tick(id);
        accounts.purgeExpiredAccount(id);
        assertTrue(resourcesRepository.findByPlayerId(id).isEmpty());
        assertTrue(mails.existsById(mailId));
    }

    @Test void cooldownKeepsSettlementAndSingleGuildRejectsNewApplications() {
        Player player = account("cooldown-account"), other = account("applicant");
        Guild guild = guilds.save(new Guild(null, "待注销军团", "", "旗", player.getId(), 1L));
        members.save(new GuildMember(null, guild.getId(), player.getId(), "leader", 1L));
        disable(player);
        assertThrows(IllegalArgumentException.class, () -> guildService.apply(other.getId(), guild.getId()));
        assertFalse(player.deletionDue(System.currentTimeMillis()));
        assertTrue(playerRepository.findDuePlayerIds(0, System.currentTimeMillis(), org.springframework.data.domain.PageRequest.of(0, 100)).contains(player.getId()));
    }
    @Autowired com.wargame.config.GameWebSocketHandler sockets;
    @Autowired com.wargame.config.JwtHandshakeInterceptor handshake;
    @Autowired ReportPrivacyService privacy;

    @Test void websocketConnectionsAndHandshakeRejectDisabledOrOldVersions() throws Exception {
        Player player = account("socket-account");
        String old = token(player);
        var session = org.mockito.Mockito.mock(org.springframework.web.socket.WebSocketSession.class);
        org.mockito.Mockito.when(session.getId()).thenReturn("account-test-socket");
        org.mockito.Mockito.when(session.isOpen()).thenReturn(true);
        org.mockito.Mockito.when(session.getAttributes()).thenReturn(Map.of("playerId", player.getId(), "token", old));
        sockets.afterConnectionEstablished(session);
        assertTrue(sockets.isPlayerOnline(player.getId()));
        disable(player);
        sockets.closeInvalidSessions();
        org.mockito.Mockito.verify(session).close(org.mockito.ArgumentMatchers.argThat(code -> code.getCode() == 4001));
        assertFalse(sockets.isPlayerOnline(player.getId()));
        var servletRequest = new MockHttpServletRequest("GET", "/ws/game");
        servletRequest.setQueryString("token=" + old);
        var request = new org.springframework.http.server.ServletServerHttpRequest(servletRequest);
        var response = new org.springframework.http.server.ServletServerHttpResponse(new org.springframework.mock.web.MockHttpServletResponse());
        assertFalse(handshake.beforeHandshake(request, response, sockets, new HashMap<>()));
        String secret = (String) auth.login(player.getUsername(), "test password", new MockHttpServletRequest()).get("recoveryToken");
        accounts.recover(secret, true);
        assertFalse(handshake.beforeHandshake(request, response, sockets, new HashMap<>()));
    }

    @Test void historicalReportKeepsBattleOutcomeButHidesDeletedParticipant() {
        Player player = account("report-deleted");
        player.setAccountStatus("DELETED"); player.setDisabled(1); playerRepository.saveAndFlush(player);
        Map<String, Object> report = new HashMap<>(Map.of("attackerName", player.getUsername(), "win", true,
                "plunder", Map.of("gold", 100), "report", "历史战斗正文"));
        privacy.anonymize(List.of(report));
        assertEquals("已注销玩家", report.get("attackerName"));
        assertEquals(true, report.get("win"));
        assertEquals(Map.of("gold", 100), report.get("plunder"));
        assertEquals("历史战斗正文", report.get("report"));
    }

    @Test void leaderCanTransferThenDeleteWithoutDamagingGuild() {
        Player leader = account("transfer-leader"), successor = account("transfer-successor");
        Guild guild = guilds.save(new Guild(null, "转让注销团", "", "旗", leader.getId(), 1L));
        members.save(new GuildMember(null, guild.getId(), leader.getId(), "leader", 1L));
        members.save(new GuildMember(null, guild.getId(), successor.getId(), "member", 2L));
        guildService.transferLeadership(leader.getId(), successor.getId());
        assertEquals(successor.getId(), guilds.findById(guild.getId()).orElseThrow().getLeaderPlayerId());
        disable(leader);
        leader.setRecoverUntil(System.currentTimeMillis() - 1);
        playerRepository.saveAndFlush(leader);
        accounts.purgeExpiredAccount(leader.getId());
        em.clear();
        assertTrue(guilds.existsById(guild.getId()));
        assertEquals("leader", members.findByPlayerId(successor.getId()).orElseThrow().getRole());
        assertTrue(members.findByPlayerId(leader.getId()).isEmpty());
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void requestAuthenticatedBeforeDeletionMustRecheckAfterAcquiringLock() throws Exception {
        Player player = account("inflight-account");
        String old = token(player);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var deletion = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
                Player current = accounts.lockPlayer(player.getId());
                current.setAccountStatus("PENDING_DELETION"); current.setDisabled(1);
                current.setRecoverUntil(System.currentTimeMillis() + 604800000L);
                current.setAuthVersion(current.getAuthVersion() + 1);
                locked.countDown();
                try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return null;
            }));
            assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var request = pool.submit(() -> http.perform(post("/api/game/settings/tax").header("Authorization", "Bearer " + old)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"tax\":10}")).andReturn().getResponse().getStatus());
            assertThrows(java.util.concurrent.TimeoutException.class, () -> request.get(150, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            deletion.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(200, request.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(30, playerRepository.findById(player.getId()).orElseThrow().getTax());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentCleanupAndRecoveryCannotResurrectExpiredAccount() throws Exception {
        Player player = account("concurrent-expired-account");
        disable(player);
        String recovery = (String) auth.login(player.getUsername(), "test password", new MockHttpServletRequest()).get("recoveryToken");
        new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
            accounts.lockPlayer(player.getId()).setRecoverUntil(System.currentTimeMillis() - 1);
            return null;
        });
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var cleanup = pool.submit(() -> { start.await(); accounts.purgeExpiredAccount(player.getId()); return true; });
            var restore = pool.submit(() -> { start.await(); return assertThrows(AccountException.class, () -> accounts.recover(recovery, true)); });
            start.countDown();
            cleanup.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(restore.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("DELETED", playerRepository.findById(player.getId()).orElseThrow().getAccountStatus());
            assertTrue(resourcesRepository.findByPlayerId(player.getId()).isEmpty());
        } finally { pool.shutdownNow(); }
    }

}
