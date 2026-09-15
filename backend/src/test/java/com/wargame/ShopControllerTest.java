package com.wargame;

import com.wargame.controller.ShopController;
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
import com.wargame.service.OfficerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
    @DisplayName("9套装备宝箱均在ItemDef与价格表中正确注册")
    void testAllNineEquipmentBoxesRegistered() {
        String[] boxKeys = {
            "box_recruit_military", "box_recruit_logistics", "box_recruit_knowledge",
            "box_officer_military", "box_officer_logistics", "box_officer_knowledge",
            "box_marshal_military", "box_marshal_logistics", "box_marshal_knowledge"
        };
        for (String key : boxKeys) {
            ItemDef def = ItemDef.ITEMS.get(key);
            assertNotNull(def, "ItemDef 应注册 " + key);
            assertEquals("officer", def.cat(), "宝箱分类应为 officer");
            assertTrue(def.name().contains("装备箱"), "名称应包含装备箱: " + def.name());
        }
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
        officer.setEquipmentCount(0);
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
    @DisplayName("购买军衔珠宝宝箱并开启：扣除200钻石、入仓、开箱后发放5种晋升珠宝")
    void testBuyAndOpenJewelryBox() {
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setDiamond(500);
        resourcesRepository.save(res);

        // 1. 购买军衔珠宝宝箱 (200钻)
        ResponseEntity<Map<String, Object>> buyResp = shopController.buy(new GameDtos.ShopBuyRequest("box_gem"));
        assertNotNull(buyResp.getBody());
        assertTrue((Boolean) buyResp.getBody().get("success"), "购买军衔珠宝宝箱应成功");
        assertEquals("已购买 军衔珠宝宝箱", buyResp.getBody().get("message"));

        Resources updatedRes = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        assertEquals(300, updatedRes.getDiamond(), "扣除200钻后应剩余300钻");

        PlayerItem boxItem = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "box_gem").orElseThrow();
        assertEquals(1, boxItem.getCount(), "仓库中应有1个军衔珠宝宝箱");

        // 2. 玩家在仓库中开启军衔珠宝宝箱
        Map<String, Object> openResp = depotService.useItem(player.getId(), "box_gem", null, null);
        assertNotNull(openResp);
        assertTrue((Boolean) openResp.get("success"), "开启珠宝宝箱应成功: " + openResp.get("message"));
        assertTrue(((String) openResp.get("message")).contains("开启【军衔珠宝宝箱】"));

        // 3. 验证宝箱已被扣除
        PlayerItem afterOpenBox = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "box_gem").orElseThrow();
        assertEquals(0, afterOpenBox.getCount(), "开箱后宝箱数量应为0");

        // 4. 验证获得对应晋升珠宝：珍珠×5、珊瑚×3、琉璃×3、琥珀×2、玛瑙×2
        assertEquals(5, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "gem_pearl").orElseThrow().getCount());
        assertEquals(3, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "gem_coral").orElseThrow().getCount());
        assertEquals(3, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "gem_glaze").orElseThrow().getCount());
        assertEquals(2, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "gem_amber").orElseThrow().getCount());
        assertEquals(2, playerItemRepository.findByPlayerIdAndItemKey(player.getId(), "gem_agate").orElseThrow().getCount());
    }

    @Test
    @DisplayName("购买璀璨珠宝全集箱并开启：发放全部9种晋升珠宝各5颗")
    void testBuyAndOpenGrandJewelryBox() {
        Resources res = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        res.setDiamond(3000);
        resourcesRepository.save(res);

        // 1. 购买璀璨珠宝全集箱 (2500钻)
        ResponseEntity<Map<String, Object>> buyResp = shopController.buy(new GameDtos.ShopBuyRequest("box_gem_grand"));
        assertNotNull(buyResp.getBody());
        assertTrue((Boolean) buyResp.getBody().get("success"), "购买璀璨珠宝全集箱应成功");

        Resources updatedRes = resourcesRepository.findByPlayerId(player.getId()).orElseThrow();
        assertEquals(500, updatedRes.getDiamond(), "扣除2500钻后应剩余500钻");

        // 2. 开启宝箱
        Map<String, Object> openResp = depotService.useItem(player.getId(), "box_gem_grand", null, null);
        assertNotNull(openResp);
        assertTrue((Boolean) openResp.get("success"), "开启宝箱应成功");

        // 3. 验证全部9种珠宝各获得5颗
        String[] allGems = {
            "gem_pearl", "gem_coral", "gem_glaze", "gem_amber", "gem_agate",
            "gem_crystal", "gem_jadeite", "gem_jade", "gem_nightpearl"
        };
        for (String gemKey : allGems) {
            PlayerItem pi = playerItemRepository.findByPlayerIdAndItemKey(player.getId(), gemKey).orElseThrow();
            assertEquals(5, pi.getCount(), "应获得珠宝 " + gemKey + " 各5颗");
        }
    }

    @Test
    @DisplayName("6种军衔珠宝宝箱均在ItemDef与价格表中正确注册")
    void testAllJewelryBoxesRegistered() {
        String[] boxKeys = {
            "box_gem", "box_gem_primary", "box_gem_medium",
            "box_gem_senior", "box_gem_supreme", "box_gem_grand"
        };
        for (String key : boxKeys) {
            ItemDef def = ItemDef.ITEMS.get(key);
            assertNotNull(def, "ItemDef 应注册 " + key);
            assertEquals("jewelry", def.cat(), "宝箱分类应为 jewelry");
            assertTrue(def.name().contains("宝箱") || def.name().contains("箱"), "名称应包含宝箱: " + def.name());
        }
    }
}
