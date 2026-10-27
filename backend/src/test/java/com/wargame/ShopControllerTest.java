package com.wargame;

import com.wargame.controller.ShopController;
import com.wargame.controller.DepotController;
import com.wargame.model.UserPrincipal;
import com.wargame.model.constants.ItemDef;
import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.Officer;
import com.wargame.model.entity.OfficerEquipment;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerItem;
import com.wargame.model.entity.Resources;
import com.wargame.repository.OfficerEquipmentRepository;
import com.wargame.repository.OfficerRepository;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.service.DepotService;
import com.wargame.service.CityScope;
import com.wargame.service.OfficerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ShopController 单元测试")
public class ShopControllerTest extends BaseServiceTest {

    @Autowired
    private ShopController shopController;

    @Autowired
    private DepotController depotController;

    @Autowired
    private CityScope cityScope;

    @Autowired
    private DepotService depotService;

    @Autowired
    private OfficerService officerService;

    @Autowired
    private OfficerRepository officerRepository;

    @Autowired
    private OfficerEquipmentRepository officerEquipmentRepository;

    @Autowired
    private PlayerItemRepository playerItemRepository;

    private Player player;

    @BeforeEach
    void setUp() {
        player = createTestPlayer("shopplayer", 30);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UserPrincipal.from(player), null, UserPrincipal.from(player).getAuthorities())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({"goldBox,80,0,0,0,0,50000", "resBox,80,50000,50000,50000,50000,0",
            "foodPack,200,600000,0,0,0,0", "steelPack,200,0,600000,0,0,0",
            "oilPack,200,0,0,600000,0,0", "rarePack,200,0,0,0,600000,0",
            "supplyPack,500,400000,400000,400000,400000,0",
            "resourcePack100w,1150,1000000,1000000,1000000,1000000,0",
            "resourcePack200w,2200,2000000,2000000,2000000,2000000,0",
            "resourcePack350w,3700,3500000,3500000,3500000,3500000,0",
            "resourcePack500w,5000,5000000,5000000,5000000,5000000,0"})
    void resourcePacksChargeAndAwardAdvertisedAmounts(String itemId, int price, long food, long steel,
                                                      long oil, long rare, long gold) {
        Resources resources = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        resources.setDiamond(10_000);
        resourcesRepository.saveAndFlush(resources);
        long beforeFood = resources.getFood(), beforeSteel = resources.getSteel(), beforeOil = resources.getOil();
        long beforeRare = resources.getRare(), beforeGold = resources.getGold();
        Map<String, Object> purchased = shopController.buy(new GameDtos.ShopBuyRequest(itemId)).getBody();
        assertNotNull(purchased);
        assertEquals(true, purchased.get("success"));
        assertEquals(price, purchased.get("price"));
        Resources purchasedResources = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        assertEquals(10_000 - price, purchasedResources.getDiamond());
        assertEquals(beforeFood, purchasedResources.getFood());
        assertEquals(true, depotService.useItem(player.getId(), itemId, null, null).get("success"));
        Resources awarded = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        assertEquals(beforeFood + food, awarded.getFood());
        assertEquals(beforeSteel + steel, awarded.getSteel());
        assertEquals(beforeOil + oil, awarded.getOil());
        assertEquals(beforeRare + rare, awarded.getRare());
        assertEquals(beforeGold + gold, awarded.getGold());
        assertEquals(0, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), itemId).orElseThrow().getCount());
    }

    @ParameterizedTest
    @CsvSource({"shield2h,2,60", "shield,8,200", "shield12h,12,280", "shield24h,24,500", "shield72h,72,1350"})
    void buyAndUseShieldTier(String itemId, int hours, int price) {
        Resources resources = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        resources.setDiamond(2000);
        resourcesRepository.save(resources);
        Map<String, Object> purchased = shopController.buy(new GameDtos.ShopBuyRequest(itemId)).getBody();
        assertNotNull(purchased);
        assertEquals(true, purchased.get("success"));
        assertEquals(price, purchased.get("price"));
        assertEquals(2000 - price, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getDiamond());
        assertEquals(1, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), itemId).orElseThrow().getCount());
        long before = System.currentTimeMillis();
        Map<String, Object> used = depotService.useItem(player.getId(), itemId, null, null);
        long after = System.currentTimeMillis();
        assertEquals(true, used.get("success"));
        long until = (Long) used.get("shieldUntil");
        assertTrue(until >= before + hours * 3600_000L);
        assertTrue(until <= after + hours * 3600_000L);
        assertEquals(0, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), itemId).orElseThrow().getCount());
        assertEquals(until, cityStateRepository.findByPlayerIdAndCitySlot(player.getId(), cityScope.slot(player.getId())).orElseThrow().getShieldUntil());
    }

    @Test
    @DisplayName("新手礼包购买入仓、开启固定发奖，重复购买和重复开启均不增加奖励")
    void buyAndOpenNewbiePackOnce() {
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setDiamond(200);
        resourcesRepository.save(res);

        Map<String, Object> buy = shopController.buy(new GameDtos.ShopBuyRequest("newbiePack")).getBody();
        assertNotNull(buy);
        assertEquals(true, buy.get("success"));
        assertEquals(99, buy.get("price"));
        Resources purchased = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        assertEquals(101, purchased.getDiamond());
        assertEquals(1000, purchased.getFood(), "购买只入仓，不提前发资源");
        assertEquals(1, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "newbiePack").orElseThrow().getCount());
        assertEquals(false, shopController.buy(new GameDtos.ShopBuyRequest("newbiePack")).getBody().get("success"));

        Map<String, Object> open = depotController.useItem(new GameDtos.DepotUseRequest("newbiePack", null, null)).getBody();
        assertNotNull(open);
        assertEquals(true, open.get("success"));
        Map<?, ?> latest = (Map<?, ?>) open.get("state");
        assertEquals(0, ((Map<?, ?>) latest.get("items")).get("newbiePack"));
        Resources awarded = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        assertEquals(21000, awarded.getFood());
        assertEquals(21000, awarded.getSteel());
        assertEquals(11000, awarded.getOil());
        assertEquals(5500, awarded.getRare());
        assertEquals(3500, awarded.getGold());
        assertEquals(101, awarded.getDiamond());

        assertEquals(false, depotService.useItem(player.getId(), "newbiePack", null, null).get("success"));
        assertEquals(false, shopController.buy(new GameDtos.ShopBuyRequest("newbiePack")).getBody().get("success"));
        assertEquals(21000, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getFood());
        assertEquals(101, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getDiamond());
        assertEquals(0, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "newbiePack").orElseThrow().getCount());
        assertEquals("resource", ItemDef.ITEMS.get("newbiePack").cat());
    }

    @Test
    @DisplayName("统帅改名卡不在商城上架且伪造购买请求不会扣钻石")
    void commanderRenameCardIsNotPurchasable() {
        Resources resources = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        resources.setDiamond(500);
        resourcesRepository.save(resources);
        Map<String, Object> result = shopController.buy(new GameDtos.ShopBuyRequest("commanderRenameCard")).getBody();
        assertNotNull(result);
        assertEquals(false, result.get("success"));
        assertEquals(500, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getDiamond());
        assertTrue(playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "commanderRenameCard").isEmpty());
    }

    @Test
    @DisplayName("钻石不足不会占用新手礼包限购资格")
    void insufficientBalanceDoesNotReserveNewbiePack() {
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setDiamond(98);
        resourcesRepository.save(res);
        assertEquals(false, shopController.buy(new GameDtos.ShopBuyRequest("newbiePack")).getBody().get("success"));
        assertTrue(playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "newbiePack").isEmpty());
        assertEquals(98, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getDiamond());
        res.setDiamond(99);
        resourcesRepository.save(res);
        assertEquals(true, shopController.buy(new GameDtos.ShopBuyRequest("newbiePack")).getBody().get("success"));
    }

    @Test
    @DisplayName("城市资源数据缺失时新手礼包不被消耗")
    void missingResourcesPreserveNewbiePack() {
        playerItemRepository.save(new PlayerItem(null, player.getId(), "newbiePack", 1, System.currentTimeMillis()));
        try (var ignored = cityScope.enter(player.getId(), 1)) {
            assertEquals(false, depotService.useItem(player.getId(), "newbiePack", null, null).get("success"));
        }
        assertEquals(1, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "newbiePack").orElseThrow().getCount());
        assertEquals(1000, resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).orElseThrow().getFood());
    }

    @Test
    @DisplayName("新手礼包资源发往当前城市，账号共享的限购记录不改变")
    void newbiePackCreditsSelectedCity() {
        Resources city = new Resources();
        city.setPlayerId(player.getId()); city.setCitySlot(1);
        city.setFood(10); city.setSteel(20); city.setOil(30); city.setRare(40); city.setGold(50);
        resourcesRepository.save(city);
        playerItemRepository.save(new PlayerItem(null, player.getId(), "newbiePack", 1, System.currentTimeMillis()));
        try (var ignored = cityScope.enter(player.getId(), 1)) {
            assertEquals(true, depotService.useItem(player.getId(), "newbiePack", null, null).get("success"));
        }
        Resources awarded = resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 1).orElseThrow();
        assertEquals(20010, awarded.getFood()); assertEquals(20020, awarded.getSteel());
        assertEquals(10030, awarded.getOil()); assertEquals(5040, awarded.getRare()); assertEquals(3050, awarded.getGold());
        assertEquals(1000, resourcesRepository.findByPlayerIdAndCitySlot(player.getId(), 0).orElseThrow().getFood());
        assertEquals(false, shopController.buy(new GameDtos.ShopBuyRequest("newbiePack")).getBody().get("success"));
    }

    @Test
    @DisplayName("已移除礼包不能再购买，余额和库存不变")
    void removedGiftProductsAreUnavailable() {
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setDiamond(5000);
        resourcesRepository.save(res);

        for (String itemId : new String[] { "annivPack", "monthCard", "warChest" }) {
            Map<String, Object> response = shopController.buy(new GameDtos.ShopBuyRequest(itemId)).getBody();
            assertNotNull(response);
            assertEquals(false, response.get("success"));
            assertEquals("无效的道具: " + itemId, response.get("message"));
            assertTrue(playerItemRepository.findByPlayerIdAndItemKey(player.getId(), itemId).isEmpty());
        }
        assertEquals(5000, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getDiamond());
    }

    @Test
    @DisplayName("已移除的月卡充值档位不能发放钻石")
    void removedMonthlyRechargePackagesAreUnavailable() {
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setDiamond(200);
        resourcesRepository.save(res);
        for (String packageId : new String[] { "mk30", "wk98" }) {
            Map<String, Object> response = shopController.recharge(
                    new GameDtos.ShopRechargeRequest(packageId, 30, 300, "模拟支付")).getBody();
            assertNotNull(response);
            assertEquals(false, response.get("success"));
            assertEquals("无效的充值档位", response.get("message"));
        }
        assertEquals(200, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getDiamond());
    }

    @Test
    @DisplayName("购买人口动员令成功，扣除100钻石并入仓")
    void testBuyPopulationOrder_success() {
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setDiamond(200);
        resourcesRepository.save(res);

        ResponseEntity<Map<String, Object>> response = shopController.buy(new GameDtos.ShopBuyRequest("populationOrder"));
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"), "购买应成功: " + response.getBody().get("message"));
        assertEquals("已购买 人口动员令", response.getBody().get("message"));

        Resources updatedRes = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        assertEquals(100, updatedRes.getDiamond(), "扣除100钻石后应剩余100");

        PlayerItem pi = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "populationOrder").orElseThrow();
        assertEquals(1, pi.getCount(), "仓库中人口动员令数量应为1");
    }

    @Test
    @DisplayName("紧急人口动员令可购买入仓并通过仓库接口增加当前城市容量30%的人口")
    void buyAndUseEmergencyPopulationOrder() {
        Resources resources = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        resources.setDiamond(200);
        resourcesRepository.save(resources);
        player.setCivilianPopulation(100);
        playerRepository.save(player);
        createBuilding(player.getId(), "house", 10);

        Map<String, Object> purchased = shopController.buy(new GameDtos.ShopBuyRequest("emergencyPopulationOrder")).getBody();
        assertNotNull(purchased);
        assertEquals(true, purchased.get("success"));
        assertEquals(100, purchased.get("price"));
        assertEquals("已购买 紧急人口动员令", purchased.get("message"));
        assertEquals(100, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getDiamond());
        assertEquals(1, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "emergencyPopulationOrder").orElseThrow().getCount());

        Map<String, Object> used = depotController.useItem(new GameDtos.DepotUseRequest("emergencyPopulationOrder", null, null)).getBody();
        assertNotNull(used);
        assertEquals(true, used.get("success"));
        Map<?, ?> state = (Map<?, ?>) used.get("state");
        assertEquals(3700, ((Map<?, ?>) state.get("population")).get("civilian"));
        assertEquals(12000, ((Map<?, ?>) state.get("population")).get("capacity"));
        assertEquals(0, ((Map<?, ?>) state.get("items")).get("emergencyPopulationOrder"));
        assertEquals(3700, playerRepository.findById(player.getId()).orElseThrow().getCivilianPopulation());
        assertEquals("util", ItemDef.ITEMS.get("emergencyPopulationOrder").cat());
        assertEquals("紧急人口动员令", ItemDef.ITEMS.get("emergencyPopulationOrder").name());

        resources = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        resources.setDiamond(99);
        resourcesRepository.save(resources);
        assertEquals(false, shopController.buy(new GameDtos.ShopBuyRequest("emergencyPopulationOrder")).getBody().get("success"));
        assertEquals(0, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "emergencyPopulationOrder").orElseThrow().getCount());
    }

    @Test
    @DisplayName("购买城市改名卡成功，扣除60钻石并入仓")
    void buyCityRenameCard() {
        Resources resources = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        resources.setDiamond(100);
        resourcesRepository.save(resources);

        Map<String, Object> response = shopController.buy(new GameDtos.ShopBuyRequest("cityRenameCard")).getBody();
        assertNotNull(response);
        assertEquals(true, response.get("success"));
        assertEquals("已购买 城市改名卡", response.get("message"));
        assertEquals(40, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getDiamond());
        assertEquals(1, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "cityRenameCard").orElseThrow().getCount());
        assertEquals("util", ItemDef.ITEMS.get("cityRenameCard").cat());
    }

    @Test
    @DisplayName("钻石不足时购买人口动员令失败")
    void testBuyPopulationOrder_insufficientDiamonds() {
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setDiamond(50);
        resourcesRepository.save(res);

        ResponseEntity<Map<String, Object>> response = shopController.buy(new GameDtos.ShopBuyRequest("populationOrder"));
        assertNotNull(response.getBody());
        assertFalse((Boolean) response.getBody().get("success"), "钻石不足时应购买失败");
        assertEquals("钻石不足", response.getBody().get("message"));
    }

    @Test
    @DisplayName("ItemDef中已正确注册populationOrder")
    void testItemDefContainsPopulationOrder() {
        ItemDef def = ItemDef.ITEMS.get("populationOrder");
        assertNotNull(def, "ItemDef.ITEMS 应包含 populationOrder");
        assertEquals("人口动员令", def.name());
        assertEquals("util", def.cat());
    }

    @Test
    @DisplayName("购买装备宝箱并开启：扣除钻石、入仓、开箱后发放整套3件装备")
    void testBuyAndOpenEquipmentBox() {
        // 1. 充足钻石购买「列兵军事装备箱」（200 钻）
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setDiamond(500);
        resourcesRepository.save(res);

        ResponseEntity<Map<String, Object>> buyResp = shopController.buy(new GameDtos.ShopBuyRequest("box_recruit_military"));
        assertNotNull(buyResp.getBody());
        assertTrue((Boolean) buyResp.getBody().get("success"), "购买宝箱应成功");
        assertEquals("已购买 列兵军事装备箱", buyResp.getBody().get("message"));

        Resources updatedRes = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        assertEquals(300, updatedRes.getDiamond(), "扣除200钻后应剩余300钻");

        PlayerItem boxItem = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "box_recruit_military").orElseThrow();
        assertEquals(1, boxItem.getCount(), "仓库中应有1个列兵军事装备箱");

        // 2. 玩家在仓库中点击使用该宝箱
        Map<String, Object> openResp = depotService.useItem(player.getId(), "box_recruit_military", null, null);
        assertNotNull(openResp);
        assertTrue((Boolean) openResp.get("success"), "开启宝箱应成功: " + openResp.get("message"));
        assertTrue(((String) openResp.get("message")).contains("开启【列兵军事装备箱】"));

        // 3. 验证宝箱已被扣除
        PlayerItem afterOpenBox = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "box_recruit_military").orElseThrow();
        assertEquals(0, afterOpenBox.getCount(), "开箱后宝箱数量应为0");

        // 4. 验证获得整套 3 件装备：列兵军刀、列兵臂章、列兵作训服
        PlayerItem weapon = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "recruit_military_weapon").orElseThrow();
        PlayerItem badge = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "recruit_military_badge").orElseThrow();
        PlayerItem coat = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "recruit_military_coat").orElseThrow();

        assertEquals(1, weapon.getCount(), "应获得武器 列兵军刀×1");
        assertEquals(1, badge.getCount(), "应获得徽章 列兵臂章×1");
        assertEquals(1, coat.getCount(), "应获得外套 列兵作训服×1");
    }

    @Test
    @DisplayName("12套装备宝箱均在ItemDef与价格表中正确注册")
    void testAllTwelveEquipmentBoxesRegistered() {
        String[] boxKeys = {
            "box_recruit_military", "box_recruit_defense", "box_recruit_logistics", "box_recruit_knowledge",
            "box_officer_military", "box_officer_defense", "box_officer_logistics", "box_officer_knowledge",
            "box_marshal_military", "box_marshal_defense", "box_marshal_logistics", "box_marshal_knowledge"
        };
        for (String key : boxKeys) {
            ItemDef def = ItemDef.ITEMS.get(key);
            assertNotNull(def, "ItemDef 应注册 " + key);
            assertEquals("officer", def.cat(), "宝箱分类应为 officer");
            assertTrue(def.name().contains("装备箱"), "名称应包含装备箱: " + def.name());
        }
    }

    @Test
    @DisplayName("购买并开启防御装备宝箱：扣除钻石，入仓并开出整套3件防御装备")
    void testBuyAndOpenDefenseEquipmentBox() {
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setDiamond(1000);
        resourcesRepository.save(res);

        ResponseEntity<Map<String, Object>> buyResp = shopController.buy(new GameDtos.ShopBuyRequest("box_recruit_defense"));
        assertNotNull(buyResp.getBody());
        assertTrue((Boolean) buyResp.getBody().get("success"), "购买列兵防御装备箱应成功");
        assertEquals("已购买 列兵防御装备箱", buyResp.getBody().get("message"));
        assertEquals(800, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getDiamond(), "应扣除200钻石");

        PlayerItem boxItem = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "box_recruit_defense").orElseThrow();
        assertEquals(1, boxItem.getCount(), "背包应有1个列兵防御装备箱");

        Map<String, Object> openResp = depotService.useItem(player.getId(), "box_recruit_defense", null, null);
        assertTrue((Boolean) openResp.get("success"), "开启装备箱应成功");
        assertTrue(openResp.get("message").toString().contains("列兵护身盾"), "开启消息应包含获得的装备");

        PlayerItem afterOpenBox = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "box_recruit_defense").orElseThrow();
        assertEquals(0, afterOpenBox.getCount(), "开启后宝箱数量应为0");

        PlayerItem weapon = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "recruit_defense_weapon").orElseThrow();
        PlayerItem badge = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "recruit_defense_badge").orElseThrow();
        PlayerItem coat = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "recruit_defense_coat").orElseThrow();

        assertEquals(1, weapon.getCount(), "应获得武器 列兵护身盾×1");
        assertEquals(1, badge.getCount(), "应获得徽章 列兵坚守勋章×1");
        assertEquals(1, coat.getCount(), "应获得外套 列兵防弹背心×1");
    }

    @Test
    @DisplayName("军官同槽位替换装备：自动卸下原装备返还背包，不触发唯一键冲突，新装备成功穿戴")
    void testOfficerReplaceEquipmentInSameSlot() {
        // 1. 创建测试军官
        Officer officer = new Officer();
        officer.setPlayerId(player.getId());
        officer.setName("测试军官");
        officer.setLevel(10);
        officer.setStar(1);
        officer.setMilitary(50);
        officer.setLogistics(50);
        officer.setKnowledge(50);
        officer.setLoyalty(100);
        officer = officerRepository.save(officer);

        // 2. 发放两把不同的武器装备到玩家背包
        long now = System.currentTimeMillis();
        playerItemRepository.save(new PlayerItem(null, player.getId(), "recruit_military_weapon", 1, now));
        playerItemRepository.save(new PlayerItem(null, player.getId(), "recruit_knowledge_weapon", 1, now));

        // 3. 第一次穿戴「列兵军事武器」
        Map<String, Object> equip1 = officerService.equip(player.getId(), officer.getId(), "recruit_military_weapon");
        assertTrue((Boolean) equip1.get("success"), "第一次穿戴应成功");
        OfficerEquipment eq1 = officerEquipmentRepository.findByPlayerIdAndOfficerId(player.getId(), officer.getId()).get(0);
        assertEquals("recruit_military_weapon", eq1.getItemKey());
        assertEquals("weapon", eq1.getSlot());

        // 4. 第二次穿戴同槽位武器「列兵学识武器」 -> 替换已有武器
        Map<String, Object> equip2 = officerService.equip(player.getId(), officer.getId(), "recruit_knowledge_weapon");
        assertTrue((Boolean) equip2.get("success"), "同槽位替换装备应成功且不报Duplicate entry");

        // 5. 验证新武器已穿戴，且槽位依然只有1件装备
        var equippedList = officerEquipmentRepository.findByPlayerIdAndOfficerId(player.getId(), officer.getId());
        assertEquals(1, equippedList.size(), "槽位应只有1件装备");
        assertEquals("recruit_knowledge_weapon", equippedList.get(0).getItemKey(), "当前应穿戴新武器");

        // 6. 验证原武器已返还背包
        PlayerItem oldWeaponItem = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "recruit_military_weapon").orElseThrow();
        assertEquals(1, oldWeaponItem.getCount(), "原武器应退回背包且数量为1");

        PlayerItem newWeaponItem = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "recruit_knowledge_weapon").orElseThrow();
        assertEquals(0, newWeaponItem.getCount(), "新武器应已被穿戴且背包数量为0");
    }

    @Test
    @DisplayName("已移除的珠宝宝箱不能购买或开启，失败不扣钻石或发奖")
    void removedJewelryBoxesCannotBeBoughtOrOpened() {
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setDiamond(5000);
        resourcesRepository.save(res);
        String[] removed = {
                "box_gem", "box_gem_primary", "box_gem_medium",
                "box_gem_senior", "box_gem_supreme", "box_gem_grand"
        };
        long itemsBefore = playerItemRepository.count();
        for (String key : removed) {
            assertNull(ItemDef.ITEMS.get(key));
            Map<String, Object> bought = shopController.buy(new GameDtos.ShopBuyRequest(key)).getBody();
            assertNotNull(bought);
            assertEquals(false, bought.get("success"));
            assertEquals(false, depotService.useItem(player.getId(), key, null, null).get("success"));
        }
        assertEquals(5000, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getDiamond());
        assertEquals(itemsBefore, playerItemRepository.count());
    }

    @Test
    @DisplayName("八个阶段礼包保留有效赠品，开启后不能重复发奖")
    void stageGiftsGrantOnlyActiveItemsAndCannotBeReopened() {
        var expectedGifts = java.util.List.of(
                Map.of("speedUp10m", 5, "box_recruit_military", 1),
                Map.of("speedUp1h", 3, "skillBook", 2),
                Map.of("speedUp1h", 5, "recruitOrd", 2, "box_officer_military", 1),
                Map.of("speedUp5h", 3, "shield", 1),
                Map.of("speedUp12h", 2, "recruitOrd", 5, "expBookAdv", 3),
                Map.of("speedUp24h", 2, "expBookAdv", 5),
                Map.of("speedUp48h", 2, "box_marshal_military", 1),
                Map.of("speedUp72h", 3, "box_marshal_defense", 1, "box_marshal_logistics", 1, "expBookMax", 2)
        );
        Map<String, Integer> expectedInventory = new java.util.LinkedHashMap<>();
        for (int stage = 1; stage <= expectedGifts.size(); stage++) {
            String key = "stage_gift_" + stage;
            playerItemRepository.save(new PlayerItem(null, player.getId(), key, 1, System.currentTimeMillis()));
            assertEquals(true, depotService.useItem(player.getId(), key, null, null).get("success"));
            expectedGifts.get(stage - 1).forEach((item, count) -> expectedInventory.merge(item, count, Integer::sum));
            assertEquals(false, depotService.useItem(player.getId(), key, null, null).get("success"));

            Map<String, Integer> inventory = new java.util.LinkedHashMap<>();
            for (PlayerItem item : playerItemRepository.findByPlayerId(player.getId())) {
                assertNotNull(ItemDef.ITEMS.get(item.getItemKey()), "礼包只能发放有效道具");
                if (item.getCount() > 0) inventory.put(item.getItemKey(), item.getCount());
            }
            assertEquals(expectedInventory, inventory, key);
        }
        for (var rank : com.wargame.model.constants.MilitaryRankDef.RANKS) {
            if (rank.tier() > 1) assertEquals(ItemDef.CAT_UTIL, ItemDef.ITEMS.get(rank.certificateKey()).cat());
        }
    }

    @Test
    @DisplayName("购买指定技能书并使用：扣除钻石、入仓并让军官学习指定技能")
    void testBuyAndUseSpecificSkillBook() {
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setDiamond(500);
        resourcesRepository.save(res);

        ResponseEntity<Map<String, Object>> buyResp = shopController.buy(new GameDtos.ShopBuyRequest("skillBook_frenzy"));
        assertNotNull(buyResp.getBody());
        assertTrue((Boolean) buyResp.getBody().get("success"), "购买全军冲锋技能书应成功");
        assertEquals("已购买 全军冲锋技能书", buyResp.getBody().get("message"));
        assertEquals(340, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getDiamond(), "应扣除160钻石");
        assertEquals(1, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "skillBook_frenzy").orElseThrow().getCount(), "技能书应入仓");

        Officer officer = createOfficer(player.getId(), "idle", 30, 30, 30);
        Map<String, Object> useResp = depotService.useItem(player.getId(), "skillBook_frenzy", officer.getId(), null);

        assertTrue((Boolean) useResp.get("success"), "使用指定技能书应成功: " + useResp.get("message"));
        assertEquals("frenzy", useResp.get("skillId"));
        assertEquals(1, useResp.get("level"));
        assertEquals(0, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "skillBook_frenzy").orElseThrow().getCount(), "使用后技能书应扣除");
        assertTrue(officerRepository.findById(officer.getId()).orElseThrow().getSkills().contains("\"frenzy\""), "军官应学习全军冲锋");
    }

    @Test
    @DisplayName("升级军官技能消耗同类型技能书并提升一级")
    void testUpgradeSkillWithMatchingBook() {
        Officer officer = createOfficer(player.getId(), "idle", 30, 30, 30);
        officer.setSkills("[{\"id\":\"frenzy\",\"lv\":1}]");
        officerRepository.save(officer);
        long now = System.currentTimeMillis();
        playerItemRepository.save(new PlayerItem(null, player.getId(), "skillBook_frenzy", 1, now));

        Map<String, Object> result = officerService.upgradeSkill(player.getId(), officer.getId(), 0, "skillBook_frenzy");

        assertTrue((Boolean) result.get("success"));
        assertEquals(2, result.get("level"));
        assertTrue(officerRepository.findById(officer.getId()).orElseThrow().getSkills().contains("\"lv\":2"));
        assertEquals(0, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "skillBook_frenzy").orElseThrow().getCount());
    }

    @Test
    @DisplayName("升级军官技能使用错误类型技能书时不扣道具")
    void testUpgradeSkillRejectsMismatchedBook() {
        Officer officer = createOfficer(player.getId(), "idle", 30, 30, 30);
        officer.setSkills("[{\"id\":\"frenzy\",\"lv\":1}]");
        officerRepository.save(officer);
        long now = System.currentTimeMillis();
        playerItemRepository.save(new PlayerItem(null, player.getId(), "skillBook_finance", 1, now));

        Map<String, Object> result = officerService.upgradeSkill(player.getId(), officer.getId(), 0, "skillBook_finance");

        assertFalse((Boolean) result.get("success"));
        assertEquals(1, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "skillBook_finance").orElseThrow().getCount());
    }

    @Test
    @DisplayName("满级技能和缺少技能书时不升级")
    void testUpgradeSkillRejectsMaxLevelAndMissingBook() {
        Officer officer = createOfficer(player.getId(), "idle", 30, 30, 30);
        officer.setSkills("[{\"id\":\"frenzy\",\"lv\":5}]");
        officerRepository.save(officer);
        playerItemRepository.save(new PlayerItem(null, player.getId(), "skillBook_frenzy", 1, System.currentTimeMillis()));

        assertFalse((Boolean) officerService.upgradeSkill(player.getId(), officer.getId(), 0, "skillBook_frenzy").get("success"));
        assertEquals(1, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "skillBook_frenzy").orElseThrow().getCount());

        officer.setSkills("[{\"id\":\"frenzy\",\"lv\":1}]");
        officerRepository.save(officer);
        playerItemRepository.tryConsume(player.getId(), "skillBook_frenzy", 1, System.currentTimeMillis());
        assertFalse((Boolean) officerService.upgradeSkill(player.getId(), officer.getId(), 0, "skillBook_frenzy").get("success"));
        assertTrue(officerRepository.findById(officer.getId()).orElseThrow().getSkills().contains("\"lv\":1"));
    }

    @Test
    @DisplayName("商城星耀符用于军官升星尝试，满星时不扣道具")
    void testBuyAndUseStarUp() {
        assertEquals("星耀符", ItemDef.ITEMS.get("starUp").name());
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setDiamond(300);
        resourcesRepository.save(res);

        Map<String, Object> buy = shopController.buy(new GameDtos.ShopBuyRequest("starUp")).getBody();
        assertNotNull(buy);
        assertEquals(true, buy.get("success"));
        assertEquals("已购买 星耀符", buy.get("message"));
        assertEquals(0, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getDiamond());
        PlayerItem item = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "starUp").orElseThrow();
        assertEquals(1, item.getCount());

        Officer officer = createOfficer(player.getId(), "idle", 30, 30, 30);
        Map<String, Object> attempt = depotService.useItem(player.getId(), "starUp", officer.getId(), null);
        assertEquals(true, attempt.get("success"));
        assertEquals(Boolean.TRUE.equals(attempt.get("upgraded")) ? 2 : 1,
                officerRepository.findById(officer.getId()).orElseThrow().getStar());
        assertEquals(0, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "starUp").orElseThrow().getCount());

        item.setCount(1);
        playerItemRepository.save(item);
        officer.setStar(5);
        officerRepository.save(officer);
        Map<String, Object> full = depotService.useItem(player.getId(), "starUp", officer.getId(), null);
        assertEquals(false, full.get("success"));
        assertEquals("该军官已满星(5★)", full.get("message"));
        assertEquals(1, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "starUp").orElseThrow().getCount());
    }

    @Test
    @DisplayName("批量购买道具成功：扣除对应倍数钻石并入仓指定数量")
    void testBatchBuyItemsSuccess() {
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setDiamond(1000);
        resourcesRepository.save(res);

        // 批量购买 5 个 10分加速符（单价 30，总价 150）
        Map<String, Object> buyResult = shopController.buy(new GameDtos.ShopBuyRequest("speedUp10m", 5)).getBody();
        assertNotNull(buyResult);
        assertTrue((Boolean) buyResult.get("success"));
        assertEquals(30, buyResult.get("price"));
        assertEquals(5, buyResult.get("count"));
        assertEquals(150, buyResult.get("totalCost"));
        assertEquals("已购买 10分加速符 ×5", buyResult.get("message"));
        assertEquals(850, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getDiamond());

        PlayerItem pi = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "speedUp10m").orElseThrow();
        assertEquals(5, pi.getCount());

        // 再批量购买 3 个 10分加速符，叠加至 8 个
        Map<String, Object> buyMore = shopController.buy(new GameDtos.ShopBuyRequest("speedUp10m", 3)).getBody();
        assertNotNull(buyMore);
        assertTrue((Boolean) buyMore.get("success"));
        assertEquals(760, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getDiamond());
        assertEquals(8, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "speedUp10m").orElseThrow().getCount());
    }

    @Test
    @DisplayName("批量购买钻石不足时拒绝购买，余额与道具数量不改变")
    void testBatchBuyInsufficientDiamonds() {
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setDiamond(100);
        resourcesRepository.save(res);

        // 购买 5 个 10分加速符需要 150 钻石，余额 100
        Map<String, Object> buyResult = shopController.buy(new GameDtos.ShopBuyRequest("speedUp10m", 5)).getBody();
        assertNotNull(buyResult);
        assertFalse((Boolean) buyResult.get("success"));
        assertEquals("钻石不足", buyResult.get("message"));
        assertEquals(100, resourcesRepository.findByPlayerId(player.getId()).orElseThrow().getDiamond());
        assertTrue(playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "speedUp10m").isEmpty());
    }

    @Test
    @DisplayName("批量购买数量非法（<=0或>999）时被拒绝")
    void testBatchBuyInvalidCount() {
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setDiamond(1000);
        resourcesRepository.save(res);

        Map<String, Object> buy0 = shopController.buy(new GameDtos.ShopBuyRequest("speedUp10m", 0)).getBody();
        // count 为 0 时 DTO count() 兜底为 1，1个购买成功
        assertNotNull(buy0);
        assertTrue((Boolean) buy0.get("success"));

        Map<String, Object> buyOver = shopController.buy(new GameDtos.ShopBuyRequest("speedUp10m", 1000)).getBody();
        assertNotNull(buyOver);
        assertFalse((Boolean) buyOver.get("success"));
        assertEquals("购买数量无效", buyOver.get("message"));
    }

    @Test
    @DisplayName("新手礼包不可批量购买")
    void testNewbiePackCannotBeBatchBought() {
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setDiamond(1000);
        resourcesRepository.save(res);

        Map<String, Object> buyResult = shopController.buy(new GameDtos.ShopBuyRequest("newbiePack", 2)).getBody();
        assertNotNull(buyResult);
        assertFalse((Boolean) buyResult.get("success"));
        assertEquals("新手礼包每个账号限购1份", buyResult.get("message"));
    }
}
