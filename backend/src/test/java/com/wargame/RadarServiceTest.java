package com.wargame;

import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import com.wargame.util.GameClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RadarServiceTest extends BaseServiceTest {
    @Autowired RadarService radar;
    @Autowired RadarContactRepository contacts;
    @Autowired GuildMemberRepository members;
    @Autowired GuildRepository guilds;
    @Autowired WorldViewService views;
    private Player observer;
    private Player ally;
    private Player attacker;
    private PlayerCity sensor;
    private PlayerCity target;
    private Long worldId;
    private Long guildId;

    @BeforeEach
    void setupRadar() {
        observer = createTestPlayer("radar-observer", 30);
        ally = createTestPlayer("radar-ally", 30);
        attacker = createTestPlayer("radar-attacker", 30);
        worldId = createTestWorld().getId();
        sensor = city(observer, 0, 99, 99);
        target = city(ally, 0, 160, 99);
        createBuilding(observer.getId(), "guild_radar", 1);
        Guild guild = new Guild(); guild.setName("radar-guild"); guild.setLeaderPlayerId(observer.getId()); guild.setCreatedAt(GameClock.now());
        guildId = guilds.save(guild).getId();
        join(observer.getId()); join(ally.getId());
    }

    private void join(Long player) {
        GuildMember member = new GuildMember(); member.setGuildId(guildId); member.setPlayerId(player); member.setJoinedAt(GameClock.now()); members.save(member);
    }

    private PlayerCity city(Player owner, int slot, int coordinateX, int coordinateY) {
        PlayerCity city = new PlayerCity(); city.setOwnerId(owner.getId()); city.setCitySlot(slot); city.setWorldId(worldId);
        city.setName("city-" + owner.getUsername() + slot); city.setX(coordinateX); city.setY(coordinateY);
        city.setArmy("{}"); city.setForts("{}"); city.setResources("{}"); return playerCityRepository.saveAndFlush(city);
    }

    private March march(String kind, Long id, String action, String route) {
        March march = new March(); march.setPlayerId(attacker.getId()); march.setTargetKind(kind); march.setTargetId(id.toString());
        march.setTargetName("受袭目标"); march.setTargetX(target.getX()); march.setTargetY(target.getY());
        march.setFromX(20); march.setFromY(99); march.setArmy("{\"infantry\":123}"); march.setAction(action);
        march.setRouteMode("land"); march.setRouteData(route); march.setStartAt(GameClock.now()); march.setArriveAt(GameClock.now() + 60_000);
        return marchRepository.saveAndFlush(march);
    }

    private March attack() { return march("player", target.getId(), "plunder", "[[20,99],[160,99]]"); }
    private Map<String, Object> state() { return radar.view(observer.getId(), 0); }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> current() { return (List<Map<String, Object>>) state().get("contacts"); }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> history() { return (List<Map<String, Object>>) state().get("history"); }
    private void enable() { radar.setEnabled(observer.getId(), sensor.getId(), true); }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void sensorRadiusIsOneGridPerBuildingLevel(int level) {
        Building building = buildingRepository.findByPlayerIdAndCitySlotAndType(observer.getId(), 0, "guild_radar").get(0);
        building.setLevel(level); buildingRepository.saveAndFlush(building);
        Map<?, ?> data = (Map<?, ?>) ((List<?>) state().get("cities")).get(0);
        assertEquals(level, data.get("level"));
        assertEquals((double) level, ((Number) data.get("radius")).doubleValue());
    }

    @Test void upgradingRadarOnlyDiscoversRoutesWithinTheNewRadius() {
        enable();
        March march = march("player", target.getId(), "plunder", "[[20,97],[160,97]]");
        march.setFromY(97); march.setTargetY(97); marchRepository.saveAndFlush(march);
        radar.reconcile(observer.getId()); assertTrue(current().isEmpty()); assertEquals(0, contacts.count());
        Building building = buildingRepository.findByPlayerIdAndCitySlotAndType(observer.getId(), 0, "guild_radar").get(0);
        building.setLevel(2); buildingRepository.saveAndFlush(building);
        radar.reconcile(observer.getId());
        assertEquals(1, current().size()); assertEquals(march.getId(), current().get(0).get("marchId"));
    }

    @Test void defaultIsClosedAndUnrelatedRoutesDoNotCreateRecords() {
        assertFalse(sensor.isRadarEnabled()); attack(); radar.reconcile(observer.getId());
        assertTrue(current().isEmpty()); assertEquals(0, contacts.count());
        enable();
        assertEquals(1, current().size());
        assertEquals(1L, state().get("unread"));
    }

    @Test void detectsTheWholeRouteImmediatelyWithoutWaitingForTheArmy() {
        enable(); March march = attack(); radar.reconcile(observer.getId());
        assertEquals(1, current().size()); assertEquals(march.getId(), current().get(0).get("marchId"));
        assertEquals("attack", current().get(0).get("threatKind")); assertFalse(current().get(0).containsKey("fromName"));
        assertFalse(current().get(0).containsKey("army")); assertFalse(current().get(0).containsKey("routeData"));
        assertFalse(current().get(0).containsKey("totalTroops"));
        assertNotNull(current().get(0).get("threatenedPlayers"));
    }

    @Test void concreteActionRequiresLevelOneInBothLiveAndFrozenIntelligence() {
        enable(); attack(); radar.reconcile(observer.getId());
        assertEquals("attack", current().get(0).get("threatKind"));
        assertFalse(current().get(0).containsKey("action"));
        createTechnology(observer.getId(), "recon_radar", 1);
        radar.reconcile(observer.getId());
        assertEquals("plunder", current().get(0).get("action"));
        radar.setEnabled(observer.getId(), sensor.getId(), false);
        assertEquals("plunder", history().get(0).get("action"));
        technologyRepository.findByPlayerIdAndType(observer.getId(), "recon_radar").get(0).setLevel(0);
        assertFalse(history().get(0).containsKey("action"));
    }

    @Test void unrelatedTargetDoesNotCreateEvenHiddenNotifications() {
        enable(); Player unrelated = createTestPlayer("radar-unrelated", 30); PlayerCity other = city(unrelated, 0, 160, 99);
        march("player", other.getId(), "plunder", "[[20,99],[160,99]]"); radar.reconcile(observer.getId());
        assertEquals(0, contacts.count()); assertEquals(0L, state().get("unread"));
    }

    @ParameterizedTest @ValueSource(strings = {"transport", "reinforce", "rebase", "gather"})
    void nonThreateningActionsNeverCreateContacts(String action) {
        enable(); march("player", target.getId(), action, "[[20,99],[160,99]]"); radar.reconcile(observer.getId());
        assertTrue(current().isEmpty()); assertEquals(0, contacts.count());
    }

    @Test void actualPolylineIsUsedInsteadOfItsEndpointChord() {
        enable(); march("player", target.getId(), "plunder", "[[20,99],[20,150],[160,150],[160,99]]");
        radar.reconcile(observer.getId()); assertTrue(current().isEmpty());
        March bent = march("player", target.getId(), "plunder", "[[20,20],[99,99],[160,20]]");
        bent.setFromY(20); bent.setTargetY(20); marchRepository.saveAndFlush(bent);
        radar.reconcile(observer.getId()); assertEquals(1, current().size());
    }

    @Test void targetInsideAndBoundaryTangencyAreBothDiscoverable() {
        target.setX(99); playerCityRepository.saveAndFlush(target); enable(); attack(); radar.reconcile(observer.getId());
        assertEquals(1, current().size());
    }

    @Test void scoutAndOccupiedWildThreatsUseDistinctKinds() {
        enable(); march("player", target.getId(), "scout", "[[20,99],[160,99]]");
        WildTile wild = new WildTile(); wild.setWorldId(worldId); wild.setType("forest"); wild.setX(160); wild.setY(99);
        wild.setOccupied(true); wild.setOccupiedBy(ally.getId()); wild = wildTileRepository.saveAndFlush(wild);
        march("wild", wild.getId(), "conquer", "[[20,99],[160,99]]"); radar.reconcile(observer.getId());
        assertEquals(Set.of("scout", "attack"), current().stream().map(item -> item.get("threatKind")).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void multipleCitiesAreSourcesForOneContactAndClosingOneKeepsTheOther() {
        PlayerCity second = city(observer, 1, 100, 99); Building building = createBuilding(observer.getId(), "guild_radar", 1);
        building.setCitySlot(1); buildingRepository.saveAndFlush(building);
        enable(); radar.setEnabled(observer.getId(), second.getId(), true); attack(); radar.reconcile(observer.getId());
        assertEquals(1, current().size()); assertEquals(2, ((List<?>) current().get(0).get("sources")).size());
        radar.setEnabled(observer.getId(), sensor.getId(), false);
        assertEquals(1, current().size()); assertEquals(1, ((List<?>) current().get(0).get("sources")).size());
        radar.setEnabled(observer.getId(), second.getId(), false);
        assertTrue(current().isEmpty()); assertEquals("monitoring_disabled", history().get(0).get("status")); assertEquals(0L, state().get("unread"));
    }

    @Test void closingFreezesSnapshotAndReopeningNeverResetsReadOrNotice() {
        createTechnology(observer.getId(), "recon_radar", 6); enable(); March march = attack(); radar.reconcile(observer.getId());
        Long id = ((Number) current().get(0).get("contactId")).longValue();
        radar.read(observer.getId(), id); contacts.findById(id).ifPresent(item -> { item.setNoticePending(false); contacts.save(item); });
        radar.setEnabled(observer.getId(), sensor.getId(), false);
        march.setArmy("{\"infantry\":999}"); march.setArriveAt(march.getArriveAt() - 5000); marchRepository.saveAndFlush(march); radar.reconcile(observer.getId());
        assertEquals(123, history().get(0).get("totalTroops")); assertEquals(0L, state().get("unread"));
        radar.setEnabled(observer.getId(), sensor.getId(), true);
        assertEquals(id, current().get(0).get("contactId")); assertEquals(999L, current().get(0).get("totalTroops"));
        assertEquals(true, current().get(0).get("read")); assertFalse(contacts.findById(id).orElseThrow().isNoticePending());
    }

    @Test void oldSnapshotNeverGainsInformationAfterTechnologyUpgrade() {
        enable(); attack(); radar.reconcile(observer.getId()); radar.setEnabled(observer.getId(), sensor.getId(), false);
        createTechnology(observer.getId(), "recon_radar", 10);
        assertFalse(history().get(0).containsKey("fromName")); assertFalse(history().get(0).containsKey("totalTroops"));
        assertFalse(history().get(0).containsKey("combatTech")); assertFalse(history().get(0).containsKey("army"));
    }

    @Test void closedRadarNeverAddsFollowupSquadsAndCompletedMarchesAreNotBackfilled() {
        enable(); March initial = attack(); radar.reconcile(observer.getId()); radar.setEnabled(observer.getId(), sensor.getId(), false);
        March ended = attack(); ended.setReturning(true); marchRepository.saveAndFlush(ended); radar.reconcile(observer.getId());
        assertEquals(1, contacts.count()); radar.setEnabled(observer.getId(), sensor.getId(), true); assertEquals(1, current().size());
        initial.setReturning(true); marchRepository.saveAndFlush(initial); radar.reconcile(observer.getId());
        assertEquals("withdrawn", history().get(0).get("status")); assertTrue(current().isEmpty());
    }

    @Test void rechecksGuildAndOwnershipBeforeReadingOrSharing() {
        enable(); attack(); radar.reconcile(observer.getId()); Long id = ((Number) current().get(0).get("contactId")).longValue();
        members.deleteByPlayerId(ally.getId()); members.flush();
        assertTrue(current().isEmpty()); assertThrows(IllegalArgumentException.class, () -> radar.shareable(observer.getId(), id));
        radar.reconcile(observer.getId()); assertEquals("monitoring_lost", history().get(0).get("status"));
        assertThrows(IllegalArgumentException.class, () -> radar.read(attacker.getId(), id));
    }

    @Test void repeatedEventsAndSameTargetSquadsKeepEveryTaskButGroupNotifications() {
        enable(); attack(); attack(); march("player", target.getId(), "scout", "[[20,99],[160,99]]");
        radar.reconcile(observer.getId()); radar.reconcile(observer.getId());
        assertEquals(3, current().size());
        assertEquals(2, contacts.findByObserverIdAndNoticePendingTrue(observer.getId()).size());
        assertEquals(2, current().stream().map(item -> item.get("reminderGroup")).distinct().count());
        assertEquals(3L, state().get("unread"));
    }

    @Test void migrationAndUpgradeRespectTheSavedSwitchAndCurrentOwner() {
        enable(); sensor.setX(180); sensor.setY(180); playerCityRepository.saveAndFlush(sensor); attack(); radar.reconcile(observer.getId()); assertTrue(current().isEmpty());
        radar.setEnabled(observer.getId(), sensor.getId(), false); sensor.setX(99); sensor.setY(99); playerCityRepository.saveAndFlush(sensor);
        buildingRepository.findByPlayerIdAndCitySlotAndType(observer.getId(), 0, "guild_radar").forEach(building -> { building.setLevel(10); buildingRepository.save(building); });
        radar.reconcile(observer.getId()); assertFalse(sensor.isRadarEnabled()); assertTrue(current().isEmpty());
        sensor.setOwnerId(attacker.getId()); assertFalse(sensor.isRadarEnabled());
        assertThrows(IllegalArgumentException.class, () -> radar.setEnabled(observer.getId(), sensor.getId(), true));
    }

    @Test void noBuildingCannotEnableAndDirectIncomingRemainsIndependent() {
        buildingRepository.deleteAll(buildingRepository.findByPlayerIdAndCitySlotAndType(observer.getId(), 0, "guild_radar")); buildingRepository.flush();
        assertThrows(IllegalArgumentException.class, () -> radar.setEnabled(observer.getId(), sensor.getId(), true));
        March own = march("player", sensor.getId(), "plunder", "[[20,99],[99,99]]"); own.setTargetX(99); marchRepository.saveAndFlush(own);
        radar.reconcile(observer.getId()); assertTrue(current().isEmpty()); assertEquals(1, views.getIncoming(observer).size());
    }

    @Test void cityDefenseRadarAloneCannotEnableOrSupplyRegionalCoverage() {
        buildingRepository.deleteAll(buildingRepository.findByPlayerIdAndCitySlotAndType(observer.getId(), 0, "guild_radar"));
        createBuilding(observer.getId(), "radar", 10); buildingRepository.flush();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> radar.setEnabled(observer.getId(), sensor.getId(), true));
        assertEquals("本城未建设联防雷达", failure.getMessage());
        Map<?, ?> data = (Map<?, ?>) ((List<?>) state().get("cities")).get(0);
        assertEquals(0, data.get("level")); assertEquals(0.0, data.get("radius"));
        sensor.setRadarEnabled(true); playerCityRepository.saveAndFlush(sensor);
        attack(); radar.reconcile(observer.getId()); assertTrue(current().isEmpty()); assertEquals(0, contacts.count());
    }

    @Test void defenseScanAndGuildCoverageUseIndependentBuildingLevels() {
        assertEquals(false, worldService.scan(observer.getId()).get("success"));
        int baseRadius = worldService.calcScanRadius(observer.getId());
        Building defense = createBuilding(observer.getId(), "radar", 7);
        assertEquals(baseRadius + 7, worldService.calcScanRadius(observer.getId()));
        enable(); attack(); radar.reconcile(observer.getId()); assertEquals(1, current().size());
        Building regional = buildingRepository.findByPlayerIdAndCitySlotAndType(observer.getId(), 0, "guild_radar").get(0);
        regional.setLevel(4); buildingRepository.saveAndFlush(regional);
        Map<?, ?> data = (Map<?, ?>) ((List<?>) state().get("cities")).get(0);
        assertEquals(4, data.get("level")); assertEquals(4.0, data.get("radius"));
        assertEquals(baseRadius + 7, worldService.calcScanRadius(observer.getId()));
        buildingRepository.delete(defense); buildingRepository.flush(); radar.reconcile(observer.getId());
        assertEquals(false, worldService.scan(observer.getId()).get("success"));
        assertEquals(1, current().size()); assertTrue(sensor.isRadarEnabled());
        regional.setLevel(10); buildingRepository.saveAndFlush(regional); radar.reconcile(observer.getId());
        assertFalse(current().get(0).containsKey("totalTroops")); assertFalse(current().get(0).containsKey("fromName"));
    }

    @Test void anotherCityCannotSupplyTheMissingGuildRadar() {
        buildingRepository.deleteAll(buildingRepository.findByPlayerIdAndCitySlotAndType(observer.getId(), 0, "guild_radar"));
        PlayerCity second = city(observer, 1, 100, 99);
        Building regional = createBuilding(observer.getId(), "guild_radar", 10);
        regional.setCitySlot(1); buildingRepository.saveAndFlush(regional);
        assertThrows(IllegalArgumentException.class, () -> radar.setEnabled(observer.getId(), sensor.getId(), true));
        radar.setEnabled(observer.getId(), second.getId(), true); attack(); radar.reconcile(observer.getId());
        assertEquals(1, current().size());
        List<?> sources = (List<?>) current().get(0).get("sources");
        assertEquals(1, sources.size()); assertEquals(second.getId(), ((Map<?, ?>) sources.get(0)).get("cityId"));
    }

    @Test void accelerationAndBattleStatusUpdateTheExistingTaskWithoutANewNotice() {
        enable(); March march = attack(); radar.reconcile(observer.getId());
        Long id = ((Number) current().get(0).get("contactId")).longValue();
        RadarContact contact = contacts.findById(id).orElseThrow(); contact.setNoticePending(false); contacts.save(contact);
        march.setArriveAt(GameClock.now() - 1); marchRepository.saveAndFlush(march); radar.reconcile(observer.getId());
        assertEquals(1, current().size()); assertEquals(id, current().get(0).get("contactId")); assertEquals(true, current().get(0).get("waitingForBattle"));
        assertTrue(contacts.findByObserverIdAndNoticePendingTrue(observer.getId()).isEmpty());
    }

    @Test void actualParticipatingGarrisonsAreIncludedInTheThreatenedPlayerSet() {
        Player supporter = createTestPlayer("radar-supporter", 30); join(supporter.getId());
        March supportMarch = new March(); supportMarch.setPlayerId(supporter.getId()); supportMarch.setTargetKind("player"); supportMarch.setTargetId(target.getId().toString());
        supportMarch.setAction("reinforce"); supportMarch.setStationed(true); supportMarch.setSupportOwnerId(ally.getId()); supportMarch.setArmy("{\"infantry\":10}"); marchRepository.saveAndFlush(supportMarch);
        enable(); attack(); radar.reconcile(observer.getId());
        assertEquals(1, current().size()); assertEquals(2, ((List<?>) current().get(0).get("threatenedPlayers")).size());
        assertEquals(1, contacts.findByObserverIdAndNoticePendingTrue(observer.getId()).size());
    }

    @Test void historicalSnapshotsKeepTheirLastSourceAndCannotBeReadWithSomeoneElsesId() {
        enable(); attack(); radar.reconcile(observer.getId()); radar.setEnabled(observer.getId(), sensor.getId(), false);
        assertTrue(((List<?>) history().get(0).get("sources")).isEmpty()); assertEquals(1, ((List<?>) history().get(0).get("snapshotSources")).size());
    }
}
