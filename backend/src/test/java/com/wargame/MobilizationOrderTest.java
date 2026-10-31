package com.wargame;

import com.wargame.model.constants.MobilizationRules;
import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.ArmyService;
import com.wargame.service.BattleActionPreferences;
import com.wargame.service.DepotService;
import com.wargame.service.MobilizationService;
import com.wargame.util.GameClock;
import com.wargame.util.JsonUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class MobilizationOrderTest extends BaseServiceTest {
    @Autowired private PlayerItemRepository items;
    @Autowired private ArmyService armies;
    @Autowired private MobilizationService mobilization;
    @Autowired private DepotService depot;
    @Autowired private BattleActionPreferences preferences;
    @Autowired private EntityManager em;
    @Autowired private com.wargame.service.CityScope cityScope;
    @Autowired private com.wargame.controller.DepotController depotController;

    private Player fixture(String item, int stock) {
        Player player = createTestPlayer("mobilization-player", 30);
        items.saveAndFlush(new PlayerItem(null, player.getId(), item, stock, 0L));
        return player;
    }
    private Player reload(Long id) { em.flush(); em.clear(); return playerRepository.findById(id).orElseThrow(); }
    private int stock(Long id, String item) { return items.findByPlayerIdAndItemKey(id, item).orElseThrow().getCount(); }

    @ParameterizedTest
    @CsvSource({"mobilizationOrder,10", "advancedMobilizationOrder,25", "rapidMobilizationOrder,50"})
    void warehouseUseGrantsAccountCapacityFor24HoursAndSameTierExtends(String item, int percent) throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        try (var ignored = GameClock.use(clock::get)) {
            Player player = fixture(item, 4);
            Long id = player.getId();
            assertEquals(25000, armies.armyCap(id));
            assertEquals(true, depot.useItem(id, item, null, null, null, 1, "warehouse-use-1").get("success"));
            player = reload(id);
            assertEquals(clock.get() + MobilizationRules.DURATION_MS, MobilizationRules.expiries(player).get(item));
            assertEquals(25000 * (100 + percent) / 100, armies.armyCap(id));
            assertEquals(armies.armyCap(id), armies.sortieCap(id));
            assertEquals(25000, armies.ordinaryArmyCap(id));
            long end = MobilizationRules.expiries(player).get(item);
            clock.addAndGet(5 * 3600_000L);
            mobilization.use(id, item, 2, "warehouse-use-2");
            player = reload(id);
            assertEquals(end + 2 * MobilizationRules.DURATION_MS, MobilizationRules.expiries(player).get(item));
            assertEquals(1, stock(id, item));
            clock.set(end + 2 * MobilizationRules.DURATION_MS);
            assertEquals(25000, armies.armyCap(id));
            assertEquals("default", preferences.get(id).formationKey());
        }
    }

    @Test
    void allCitiesShareBuffButUseTheirOwnCommanderBonus() {
        Long id = fixture("rapidMobilizationOrder", 1).getId();
        var commander = createOfficer(id, "commander", 30, 30, 30);
        commander.setCitySlot(1);
        commander.setSkills("[{\"id\":\"leadership\",\"lv\":5}]");
        officerRepository.saveAndFlush(commander);
        mobilization.use(id, "rapidMobilizationOrder", 1, "all-cities-use");
        try (var city = cityScope.enter(id, 0)) {
            assertEquals(37500, armies.armyCap(id));
        }
        try (var city = cityScope.enter(id, 1)) {
            assertEquals(45000, armies.armyCap(id));
            assertEquals(45000, armies.sortieCap(id));
        }
    }

    @Test
    void controllerReturnsPersistedAccountTimersAndRejectsFractionalCount() {
        Player player = fixture("mobilizationOrder", 3);
        var principal = com.wargame.model.UserPrincipal.from(player);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        try {
            assertThrows(IllegalArgumentException.class, () -> depotController.useItem(
                    new com.wargame.model.dto.GameDtos.DepotUseRequest("mobilizationOrder", null, null, null, 1.5, "fractional-use")));
            var result = depotController.useItem(new com.wargame.model.dto.GameDtos.DepotUseRequest(
                    "mobilizationOrder", null, null, null, 2, "controller-use")).getBody();
            assertNotNull(result);
            assertEquals(true, result.get("success"));
            var state = (Map<?, ?>) result.get("state");
            var playerState = (Map<?, ?>) state.get("player");
            assertTrue(((Number) ((Map<?, ?>) playerState.get("mobilizationExpiries")).get("mobilizationOrder")).longValue() > GameClock.now());
            assertEquals(1, stock(player.getId(), "mobilizationOrder"));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void differentTiersCountIndependentlyAndFallBackWithoutAddingPercentages() throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        try (var ignored = GameClock.use(clock::get)) {
            Long id = fixture("mobilizationOrder", 2).getId();
            items.saveAndFlush(new PlayerItem(null, id, "rapidMobilizationOrder", 1, 0L));
            mobilization.use(id, "mobilizationOrder", 2, "normal-use-1");
            long cycle = reload(id).getMobilizationCycle();
            mobilization.use(id, "rapidMobilizationOrder", 1, "rapid-use-1");
            assertEquals(37500, armies.armyCap(id));
            assertEquals(cycle, reload(id).getMobilizationCycle());
            clock.addAndGet(MobilizationRules.DURATION_MS);
            assertEquals(27500, armies.armyCap(id));
            assertEquals(cycle, reload(id).getMobilizationCycle());
            clock.addAndGet(MobilizationRules.DURATION_MS);
            assertEquals(25000, armies.armyCap(id));
        }
    }

    @Test
    void mobilizationExpiryChangesDispatchCapacityButNotDefenseOrActionDrafts() throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        try (var ignored = GameClock.use(clock::get)) {
            Long id = fixture("rapidMobilizationOrder", 2).getId();
            Player player = reload(id);
            player.setSortieArmy("{\"infantry\":0}");
            player.setMobilizationSortieArmy("{}");
            playerRepository.saveAndFlush(player);
            var army = Map.of("infantry", 100000, "rocket", 100000);
            mobilization.use(id, "rapidMobilizationOrder", 1, "defense-use-1");
            assertEquals(37500, armies.armyCap(id));
            var draft = preferences.get(id);
            assertNull(draft.sortieArmy());
            assertEquals(army, preferences.selectSortieArmy(id, army));
            clock.addAndGet(MobilizationRules.DURATION_MS);
            assertEquals(25000, armies.armyCap(id));
            assertEquals(army, preferences.selectSortieArmy(id, army));
            preferences.save(id, new BattleActionPreferences.Preferences(draft.outgoing(), draft.defending(), Map.of(), "mobilization:old"));
            assertEquals(draft.defending(), preferences.get(id).defending());
            mobilization.use(id, "rapidMobilizationOrder", 1, "defense-use-2");
            assertEquals(army, preferences.selectSortieArmy(id, army));
            assertEquals("{\"infantry\":0}", reload(id).getSortieArmy());
            assertEquals("{}", reload(id).getMobilizationSortieArmy());
        }
    }

    @Test
    void retriesNeverDoubleSpendAndDifferentPayloadCannotReuseRequest() {
        Long id = fixture("mobilizationOrder", 3).getId();
        mobilization.use(id, "mobilizationOrder", 1, "duplicate-use");
        long end = MobilizationRules.expiries(reload(id)).get("mobilizationOrder");
        mobilization.use(id, "mobilizationOrder", 1, "another-use");
        mobilization.use(id, "mobilizationOrder", 1, "duplicate-use");
        assertEquals(1, stock(id, "mobilizationOrder"));
        assertEquals(end + MobilizationRules.DURATION_MS, MobilizationRules.expiries(reload(id)).get("mobilizationOrder"));
        assertThrows(IllegalArgumentException.class, () -> mobilization.use(id, "mobilizationOrder", 2, "duplicate-use"));
    }

    @Test
    void invalidQuantityOrInsufficientStockNeverStartsBuff() {
        Long id = fixture("mobilizationOrder", 1).getId();
        for (int count : new int[]{0, -1, 10001, 2})
            assertThrows(IllegalArgumentException.class, () -> mobilization.use(id, "mobilizationOrder", count, "invalid-count"));
        assertEquals(1, stock(id, "mobilizationOrder"));
        assertEquals(25000, armies.armyCap(id));
    }

    @Test
    void alreadyDispatchedArmySurvivesExpiryAndNewDispatchUsesOrdinaryCap() throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        try (var ignored = GameClock.use(clock::get)) {
            Long id = fixture("rapidMobilizationOrder", 1).getId();
            createArmyUnit(id, "infantry", 100000);
            giveResources(id, 100000, 100000, 1000000, 100000, 100000);
            var tile = createWildTile(createTestWorld().getId(), "forest", 15, 15, 1, Map.of(), 1000);
            mobilization.use(id, "rapidMobilizationOrder", 1, "dispatch-use");
            var march = marchService.createDispatch(id, new DispatchRequest("wild", tile.getId(), "conquer", Map.of("infantry", 37500), null, null));
            assertEquals(0, stock(id, "rapidMobilizationOrder"));
            clock.addAndGet(MobilizationRules.DURATION_MS);
            assertEquals(Map.of("infantry", 37500), JsonUtil.parseIntMap(marchRepository.findById(march.getId()).orElseThrow().getArmy()));
            assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(id,
                    new DispatchRequest("wild", tile.getId(), "conquer", Map.of("infantry", 25001), null, null)));
            assertEquals(62500, armyUnitRepository.findByPlayerIdAndType(id, "infantry").get(0).getCount());
        }
    }

    @Test
    void legacyPerMarchItemIsRejectedBeforeInventoryChanges() {
        Long id = fixture("mobilizationOrder", 1).getId();
        var request = new DispatchRequest("wild", 1L, "conquer", Map.of("infantry", 1), null, null,
                null, null, null, null, null, null, null, null, "mobilizationOrder");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(id, request)).getMessage().contains("仓库启用"));
        assertEquals(1, stock(id, "mobilizationOrder"));
    }
}
