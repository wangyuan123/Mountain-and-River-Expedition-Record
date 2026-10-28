package com.wargame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.controller.ShopController;
import com.wargame.model.UserPrincipal;
import com.wargame.model.constants.ItemDef;
import com.wargame.model.dto.DispatchRequest;
import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.PlayerItem;
import com.wargame.model.entity.WildTile;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.ArmyService;
import com.wargame.service.DepotService;
import com.wargame.util.JsonUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MobilizationOrderTest extends BaseServiceTest {
    @Autowired private PlayerItemRepository items;
    @Autowired private ArmyService armyService;
    @Autowired private DepotService depotService;
    @Autowired private ShopController shopController;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager em;

    private Long playerId;
    private WildTile tile;
    private int baseCap;

    @BeforeEach
    void setUp() {
        var player = createTestPlayer("mobilization-player", 30);
        playerId = player.getId();
        baseCap = armyService.armyCap(playerId);
        tile = createWildTile(createTestWorld().getId(), "grainfield", 15, 15, 1, Map.of("infantry", 10), 1000);
        createArmyUnit(playerId, "infantry", 100_000);
        giveResources(playerId, 100_000, 100_000, 1_000_000, 100_000, 100_000);
        var resources = resourcesRepository.findByPlayerId(playerId).orElseThrow();
        resources.setDiamond(5000);
        resourcesRepository.save(resources);
        var principal = UserPrincipal.from(player);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({"mobilizationOrder,10,80,战争动员令", "advancedMobilizationOrder,25,220,高级战争动员令",
            "rapidMobilizationOrder,50,500,极速战争动员令"})
    void purchaseChargesAdvertisedPriceAndDepotCannotActivate(String item, int percent, int price, String name) {
        var result = shopController.buy(new GameDtos.ShopBuyRequest(item, 2)).getBody();
        assertNotNull(result);
        assertEquals(true, result.get("success"));
        assertEquals(price, result.get("price"));
        assertEquals(2 * price, result.get("totalCost"));
        assertEquals(5000 - 2 * price, resourcesRepository.findByPlayerId(playerId).orElseThrow().getDiamond());
        assertEquals(2, stock(item));
        assertEquals(name, ItemDef.ITEMS.get(item).name());
        assertTrue(ItemDef.ITEMS.get(item).desc().contains("提高" + percent + "%"));
        assertEquals("util", ItemDef.ITEMS.get(item).cat());
        var used = depotService.useItem(playerId, item, null, null);
        assertEquals(false, used.get("success"));
        assertEquals("战争动员令仅可在进攻出征时选择", used.get("message"));
        assertEquals(2, stock(item));
    }

    @ParameterizedTest
    @CsvSource({"mobilizationOrder,10", "advancedMobilizationOrder,25", "rapidMobilizationOrder,50"})
    void dispatchAtTemporaryCapConsumesExactlyOneAndKeepsPermanentCap(String item, int percent) {
        giveOrder(item, 3);
        int cap = baseCap * (100 + percent) / 100;
        var march = marchService.createDispatch(playerId, request("wild", "conquer", cap, item, null));
        em.flush();
        em.clear();
        assertEquals(cap, JsonUtil.parseIntMap(marchRepository.findById(march.getId()).orElseThrow().getArmy()).get("infantry"));
        assertEquals(100_000 - cap, armyUnitRepository.findByPlayerIdAndType(playerId, "infantry").get(0).getCount());
        assertEquals(2, stock(item));
        assertEquals(baseCap, armyService.armyCap(playerId));
    }

    @ParameterizedTest
    @CsvSource({"mobilizationOrder,10", "advancedMobilizationOrder,25", "rapidMobilizationOrder,50"})
    void exceedingTemporaryCapDoesNotStackOrdersOrSpendAnything(String item, int percent) {
        giveOrder(item, 5);
        long oil = getResources(playerId).getOil();
        int cap = baseCap * (100 + percent) / 100;
        var error = assertThrows(IllegalArgumentException.class,
                () -> marchService.createDispatch(playerId, request("wild", "conquer", cap + 1, item, null)));
        assertTrue(error.getMessage().startsWith("出征兵力超过带兵上限 " + cap));
        assertUnchanged(item, 5, oil);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void withinBaseCapNeverRequiresOrConsumesOrder(int stock) {
        giveOrder("mobilizationOrder", stock);
        var march = marchService.createDispatch(playerId,
                request("wild", "conquer", baseCap, "mobilizationOrder", null));
        assertNotNull(march.getId());
        assertEquals(stock, stock("mobilizationOrder"));
    }

    @Test
    void noOrderStillRejectsOverBaseCap() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> marchService.createDispatch(playerId, request("wild", "conquer", baseCap + 1, null, null)));
        assertTrue(error.getMessage().startsWith("出征兵力超过带兵上限 " + baseCap));
    }

    @Test
    void missingInventoryRejectsWithoutSpendingTroopsOrFuel() {
        long oil = getResources(playerId).getOil();
        var error = assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(playerId,
                request("wild", "conquer", baseCap + 1, "rapidMobilizationOrder", null)));
        assertEquals("战争动员令数量不足", error.getMessage());
        assertEquals(100_000, armyUnitRepository.findByPlayerIdAndType(playerId, "infantry").get(0).getCount());
        assertEquals(oil, getResources(playerId).getOil());
        assertTrue(marchRepository.findByPlayerId(playerId).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"scout", "gather", "station", "transport", "rebase", "reinforce", "intercept_scout"})
    void nonAttacksRejectMobilizationBeforeSpendingInventory(String action) {
        giveOrder("rapidMobilizationOrder", 1);
        long oil = getResources(playerId).getOil();
        var error = assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(playerId,
                request("gather".equals(action) ? "wild_gather" : "wild", action, 1, "rapidMobilizationOrder", null)));
        assertEquals("战争动员令仅可用于进攻出征", error.getMessage());
        assertUnchanged("rapidMobilizationOrder", 1, oil);
    }

    @ParameterizedTest
    @ValueSource(strings = {"conquer", "plunder", "feint", "harass", "guerrilla"})
    void allOffensiveModesCanUseMobilization(String action) {
        giveOrder("mobilizationOrder", 1);
        var march = marchService.createDispatch(playerId,
                request("wild", action, baseCap + 1, "mobilizationOrder", null));
        assertEquals(action, march.getAction());
        assertEquals(0, stock("mobilizationOrder"));
    }

    @Test
    void resourceFailureDoesNotConsumeOrder() {
        giveOrder("rapidMobilizationOrder", 1);
        giveResources(playerId, 100_000, 100_000, 0, 100_000, 100_000);
        var error = assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(playerId,
                request("wild", "conquer", baseCap + 1, "rapidMobilizationOrder", Map.of("oil", 1))));
        assertTrue(error.getMessage().startsWith("资源不足: oil"));
        assertUnchanged("rapidMobilizationOrder", 1, 0);
    }

    @Test
    void mobilizedDispatchDeductsPlannedFuelAndAssignsCommander() {
        giveOrder("advancedMobilizationOrder", 1);
        createArmyUnit(playerId, "truck", 10);
        var officer = createOfficer(playerId, "idle", 30, 30, 30);
        var request = new DispatchRequest("wild", tile.getId(), "conquer", Map.of("infantry", baseCap, "truck", 10),
                officer.getId(), null, null, null, null, null, null, null, null, null, "advancedMobilizationOrder");
        int fuel = ((Number) marchService.previewRoute(playerId, request).get("oil")).intValue();
        assertTrue(fuel > 0);
        long oil = getResources(playerId).getOil();
        var march = marchService.createDispatch(playerId, request);
        em.flush();
        em.clear();
        assertEquals(0, stock("advancedMobilizationOrder"));
        assertEquals(oil - fuel, getResources(playerId).getOil());
        assertEquals("march", officerRepository.findById(officer.getId()).orElseThrow().getRole());
        assertEquals(officer.getId(), march.getCommanderId());
    }

    @Test
    void invalidCargoDoesNotConsumeOrder() {
        giveOrder("rapidMobilizationOrder", 1);
        long oil = getResources(playerId).getOil();
        var error = assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(playerId,
                request("wild", "conquer", baseCap + 1, "rapidMobilizationOrder", Map.of("food", Integer.MAX_VALUE))));
        assertTrue(error.getMessage().startsWith("携带资源超出负重上限"));
        assertUnchanged("rapidMobilizationOrder", 1, oil);
    }

    @Test
    void unknownItemCannotGrantCapacity() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> marchService.createDispatch(playerId, request("wild", "conquer", baseCap + 1, "marchOrd", null)));
        assertEquals("战争动员令类型无效", error.getMessage());
    }

    @Test
    void gatherTargetCannotUseOffensiveActionToEnableMobilization() {
        var error = assertThrows(IllegalArgumentException.class, () -> marchService.createDispatch(playerId,
                request("wild_gather", "conquer", baseCap + 1, "mobilizationOrder", null)));
        assertEquals("战争动员令仅可用于进攻出征", error.getMessage());
    }

    @Test
    void jsonContractPreservesLegacyRequestsAndSelectedItem() throws Exception {
        String json = "{\"targetKind\":\"wild\",\"targetId\":" + tile.getId() + ",\"action\":\"conquer\",\"army\":{\"infantry\":1}";
        assertNull(objectMapper.readValue(json + "}", DispatchRequest.class).mobilizationOrderItem());
        var selected = objectMapper.readValue(json + ",\"mobilizationOrderItem\":\"advancedMobilizationOrder\"}", DispatchRequest.class);
        assertEquals("advancedMobilizationOrder", selected.mobilizationOrderItem());
        assertNotNull(marchService.createDispatch(playerId, selected).getId());
    }

    private DispatchRequest request(String kind, String action, int count, String order, Map<String, Integer> cargo) {
        return new DispatchRequest(kind, tile.getId(), action, Map.of("infantry", count), null, cargo,
                null, null, null, null, null, null, null, null, order);
    }

    private void giveOrder(String item, int count) {
        items.saveAndFlush(new PlayerItem(null, playerId, item, count, System.currentTimeMillis()));
    }

    private int stock(String item) {
        return items.findByPlayerIdAndItemKey(playerId, item).orElseThrow().getCount();
    }

    private void assertUnchanged(String item, int count, long oil) {
        assertEquals(count, stock(item));
        assertEquals(100_000, armyUnitRepository.findByPlayerIdAndType(playerId, "infantry").get(0).getCount());
        assertEquals(oil, getResources(playerId).getOil());
        assertTrue(marchRepository.findByPlayerId(playerId).isEmpty());
    }
}
