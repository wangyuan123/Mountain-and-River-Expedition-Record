package com.wargame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.config.GameWebSocketHandler;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.GameClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RadarEventsTest extends BaseServiceTest {
    @Autowired RadarService radar;
    @Autowired RadarDeliveryService delivery;
    @Autowired RadarContactRepository contacts;
    @Autowired GuildRepository guilds;
    @Autowired GuildMemberRepository members;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @SpyBean GameWebSocketHandler socket;

    private record Scenario(Long observer, Long attacker, Long target, Long sensor) {}

    private Scenario setup() {
        return new TransactionTemplate(transactions).execute(transaction -> {
            String suffix = UUID.randomUUID().toString().substring(0, 6);
            Player observer = createTestPlayer("ob" + suffix, 30), ally = createTestPlayer("al" + suffix, 30), attacker = createTestPlayer("at" + suffix, 30);
            Long world = createTestWorld().getId();
            PlayerCity sensor = city(observer.getId(), world, 99), target = city(ally.getId(), world, 160);
            createBuilding(observer.getId(), "guild_radar", 1);
            Guild guild = new Guild(); guild.setName("g" + suffix); guild.setLeaderPlayerId(observer.getId()); guild.setCreatedAt(GameClock.now()); guild = guilds.save(guild);
            for (Long owner : List.of(observer.getId(), ally.getId())) {
                GuildMember member = new GuildMember(); member.setGuildId(guild.getId()); member.setPlayerId(owner); member.setJoinedAt(GameClock.now()); members.save(member);
            }
            radar.setEnabled(observer.getId(), sensor.getId(), true);
            return new Scenario(observer.getId(), attacker.getId(), target.getId(), sensor.getId());
        });
    }

    private PlayerCity city(Long owner, Long world, int coordinateX) {
        PlayerCity city = new PlayerCity(); city.setWorldId(world); city.setOwnerId(owner); city.setCitySlot(0); city.setName("雷达测试城");
        city.setX(coordinateX); city.setY(99); city.setForts("{}"); city.setResources("{}"); return playerCityRepository.save(city);
    }

    private March attack(Scenario scenario) {
        March march = new March(); march.setPlayerId(scenario.attacker()); march.setTargetKind("player"); march.setTargetId(scenario.target().toString());
        march.setTargetName("团友城"); march.setTargetX(160); march.setTargetY(99); march.setFromX(20); march.setFromY(99);
        march.setRouteMode("land"); march.setRouteData("[[20,99],[160,99]]"); march.setAction("plunder"); march.setArmy("{\"infantry\":100}");
        march.setStartAt(GameClock.now()); march.setArriveAt(GameClock.now() + 60_000); return marchRepository.saveAndFlush(march);
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void committedMarchIsDiscoveredWithoutAnyClientReadAndRollbackCreatesNothing() {
        Scenario scenario = setup();
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).execute(transaction -> {
            attack(scenario); throw new IllegalStateException("rollback");
        }));
        assertTrue(contacts.findByObserverIdOrderByFirstSeenAtDescIdDesc(scenario.observer()).isEmpty());
        new TransactionTemplate(transactions).execute(transaction -> attack(scenario));
        assertEquals(1, contacts.findByObserverIdOrderByFirstSeenAtDescIdDesc(scenario.observer()).size());
        assertTrue(contacts.findByObserverIdOrderByFirstSeenAtDescIdDesc(scenario.observer()).get(0).isNoticePending());
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void onlyCommittedGuildRadarChangesExpandCoverageAndRemovalStopsMonitoring() {
        Scenario scenario = setup();
        new TransactionTemplate(transactions).execute(transaction -> {
            March march = attack(scenario); march.setFromY(97); march.setTargetY(97);
            march.setRouteData("[[20,97],[160,97]]"); marchRepository.saveAndFlush(march);
            return null;
        });
        assertTrue(contacts.findByObserverIdOrderByFirstSeenAtDescIdDesc(scenario.observer()).isEmpty());
        new TransactionTemplate(transactions).execute(transaction -> createBuilding(scenario.observer(), "radar", 10));
        assertTrue(contacts.findByObserverIdOrderByFirstSeenAtDescIdDesc(scenario.observer()).isEmpty());
        new TransactionTemplate(transactions).execute(transaction -> {
            Building regional = buildingRepository.findByPlayerIdAndCitySlotAndType(scenario.observer(), 0, "guild_radar").get(0);
            regional.setLevel(2); buildingRepository.saveAndFlush(regional); return null;
        });
        var known = contacts.findByObserverIdOrderByFirstSeenAtDescIdDesc(scenario.observer());
        assertEquals(1, known.size()); assertEquals("active", known.get(0).getStatus());
        new TransactionTemplate(transactions).execute(transaction -> {
            buildingRepository.deleteAll(buildingRepository.findByPlayerIdAndCitySlotAndType(scenario.observer(), 0, "guild_radar"));
            buildingRepository.flush(); return null;
        });
        var ended = contacts.findByObserverIdOrderByFirstSeenAtDescIdDesc(scenario.observer());
        assertEquals(1, ended.size()); assertEquals("monitoring_disabled", ended.get(0).getStatus()); assertFalse(ended.get(0).isNoticePending());
        assertEquals(10, buildingRepository.findByPlayerIdAndCitySlotAndType(scenario.observer(), 0, "radar").get(0).getLevel());
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void closeRevokesQueuedNoticeAndAFormerTaskNeverAlertsAgainAfterReopening() {
        Scenario scenario = setup(); new TransactionTemplate(transactions).execute(transaction -> attack(scenario));
        RadarContact contact = contacts.findByObserverIdOrderByFirstSeenAtDescIdDesc(scenario.observer()).get(0);
        radar.setEnabled(scenario.observer(), scenario.sensor(), false);
        doReturn(true).when(socket).isPlayerOnline(scenario.observer());
        doReturn(true).when(socket).trySendToPlayer(eq(scenario.observer()), anyString());
        clearInvocations(socket); delivery.deliver(scenario.observer());
        var messages = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(socket).trySendToPlayer(eq(scenario.observer()), messages.capture());
        try {
            var payload = mapper.readTree(messages.getValue()).path("data");
            assertEquals(0, payload.path("alerts").size()); assertEquals(0, payload.path("contacts").size()); assertEquals(0, payload.path("unread").asInt());
        } catch (Exception failure) { fail(failure); }
        radar.setEnabled(scenario.observer(), scenario.sensor(), true);
        assertFalse(contacts.findById(contact.getId()).orElseThrow().isNoticePending());
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void closingTheLastRadarPushesTheDisabledStateToOtherOnlineClients() throws Exception {
        Scenario scenario = setup(); new TransactionTemplate(transactions).execute(transaction -> attack(scenario));
        doReturn(true).when(socket).isPlayerOnline(scenario.observer());
        doReturn(true).when(socket).trySendToPlayer(eq(scenario.observer()), anyString());
        clearInvocations(socket);
        radar.setEnabled(scenario.observer(), scenario.sensor(), false);
        var messages = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(socket).trySendToPlayer(eq(scenario.observer()), messages.capture());
        var payload = mapper.readTree(messages.getValue()).path("data");
        assertFalse(payload.path("cities").get(0).path("enabled").asBoolean());
        assertEquals(0, payload.path("contacts").size());
        assertEquals(0, payload.path("alerts").size());
        assertEquals(0, payload.path("unread").asInt());
        assertEquals("monitoring_disabled", payload.path("history").get(0).path("status").asText());
        assertFalse(radar.monitoredPlayers().contains(scenario.observer()));
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deliveryUsesTheObserversPermissionAndSendsTheFirstNoticeOnce() throws Exception {
        Scenario scenario = setup(); new TransactionTemplate(transactions).execute(transaction -> attack(scenario));
        doReturn(true).when(socket).isPlayerOnline(scenario.observer()); clearInvocations(socket);
        doReturn(true).when(socket).trySendToPlayer(eq(scenario.observer()), anyString());
        delivery.deliver(scenario.observer()); delivery.deliver(scenario.observer());
        var messages = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(socket, times(2)).trySendToPlayer(eq(scenario.observer()), messages.capture());
        var first = mapper.readTree(messages.getAllValues().get(0)).path("data");
        assertEquals(1, first.path("alerts").size()); assertFalse(first.path("alerts").get(0).has("fromName")); assertFalse(first.path("alerts").get(0).has("army"));
        assertEquals(0, mapper.readTree(messages.getAllValues().get(1)).path("data").path("alerts").size());
        assertFalse(contacts.findByObserverIdOrderByFirstSeenAtDescIdDesc(scenario.observer()).get(0).isNoticePending());
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedSocketDeliveryKeepsThePendingNoticeForRecovery() {
        Scenario scenario = setup(); new TransactionTemplate(transactions).execute(transaction -> attack(scenario));
        doReturn(true).when(socket).isPlayerOnline(scenario.observer());
        doReturn(false).when(socket).trySendToPlayer(eq(scenario.observer()), anyString());
        delivery.deliver(scenario.observer());
        assertTrue(contacts.findByObserverIdOrderByFirstSeenAtDescIdDesc(scenario.observer()).get(0).isNoticePending());
        doReturn(true).when(socket).trySendToPlayer(eq(scenario.observer()), anyString());
        delivery.deliver(scenario.observer());
        assertFalse(contacts.findByObserverIdOrderByFirstSeenAtDescIdDesc(scenario.observer()).get(0).isNoticePending());
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deliveryExceptionRollsBackTheNoticeAcknowledgementAndCanBeRetried() {
        Scenario scenario = setup(); new TransactionTemplate(transactions).execute(transaction -> attack(scenario));
        doReturn(true).when(socket).isPlayerOnline(scenario.observer());
        doThrow(new IllegalStateException("socket unavailable")).when(socket).trySendToPlayer(eq(scenario.observer()), anyString());
        assertThrows(IllegalStateException.class, () -> delivery.deliver(scenario.observer()));
        assertTrue(contacts.findByObserverIdOrderByFirstSeenAtDescIdDesc(scenario.observer()).get(0).isNoticePending());
        doReturn(true).when(socket).trySendToPlayer(eq(scenario.observer()), anyString());
        delivery.deliver(scenario.observer());
        assertFalse(contacts.findByObserverIdOrderByFirstSeenAtDescIdDesc(scenario.observer()).get(0).isNoticePending());
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDeliveriesOnlySendOneFirstNotice() throws Exception {
        Scenario scenario = setup(); new TransactionTemplate(transactions).execute(transaction -> attack(scenario));
        doReturn(true).when(socket).isPlayerOnline(scenario.observer());
        doReturn(true).when(socket).trySendToPlayer(eq(scenario.observer()), anyString());
        clearInvocations(socket);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(); delivery.deliver(scenario.observer()); return null; });
            var second = pool.submit(() -> { start.await(); delivery.deliver(scenario.observer()); return null; });
            start.countDown();
            first.get(15, java.util.concurrent.TimeUnit.SECONDS); second.get(15, java.util.concurrent.TimeUnit.SECONDS);
            var messages = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(socket, times(2)).trySendToPlayer(eq(scenario.observer()), messages.capture());
            int notices = 0;
            for (String message : messages.getAllValues()) notices += mapper.readTree(message).path("data").path("alerts").size();
            assertEquals(1, notices);
            assertFalse(contacts.findByObserverIdOrderByFirstSeenAtDescIdDesc(scenario.observer()).get(0).isNoticePending());
        } finally { pool.shutdownNow(); }
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentReconciliationKeepsOneContactPerRealMarch() throws Exception {
        Scenario scenario = setup();
        new TransactionTemplate(transactions).execute(transaction -> { attack(scenario); attack(scenario); return null; });
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> delivery.refresh(scenario.observer()));
            var second = pool.submit(() -> delivery.refresh(scenario.observer()));
            first.get(15, java.util.concurrent.TimeUnit.SECONDS); second.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(2, contacts.findByObserverIdOrderByFirstSeenAtDescIdDesc(scenario.observer()).size());
            assertEquals(1, contacts.findByObserverIdAndNoticePendingTrue(scenario.observer()).size());
        } finally { pool.shutdownNow(); }
    }
}
