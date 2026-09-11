package com.wargame.controller;

import com.wargame.model.dto.GameDtos;
import com.wargame.model.entity.PlayerItem;
import com.wargame.model.entity.Resources;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.repository.ResourcesRepository;
import com.wargame.service.AuthService;
import com.wargame.service.GameStateService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 商城控制器 - 处理钻石充值和道具购买。
 * 所有操作持久化到数据库，刷新页面不丢失。
 */
@RestController
@RequestMapping("/api/game/shop")
public class ShopController {

    private final AuthService authService;
    private final ResourcesRepository resourcesRepository;
    private final PlayerItemRepository playerItemRepository;
    private final GameStateService gameStateService;

    public ShopController(AuthService authService,
                          ResourcesRepository resourcesRepository,
                          PlayerItemRepository playerItemRepository,
                          GameStateService gameStateService) {
        this.authService = authService;
        this.resourcesRepository = resourcesRepository;
        this.playerItemRepository = playerItemRepository;
        this.gameStateService = gameStateService;
    }

    /**
     * 模拟充值：不接入第三方支付，点击确认后直接给当前玩家增加对应钻石。
     */
    @PostMapping("/recharge")
    @Transactional
    public ResponseEntity<Map<String, Object>> recharge(@RequestBody GameDtos.ShopRechargeRequest request) {
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = new LinkedHashMap<>();
        Integer diamonds = ShopItemPrices.getRechargeDiamond(request.pkgId());
        if (diamonds == null) {
            result.put("success", false);
            result.put("message", "无效的充值档位");
            return ResponseEntity.ok(result);
        }

        Resources res = resourcesRepository.findByPlayerId(playerId).orElse(null);
        if (res == null) {
            result.put("success", false);
            result.put("message", "玩家资源数据不存在");
            return ResponseEntity.ok(result);
        }

        int current = res.getDiamond() != null ? res.getDiamond() : 0;
        res.setDiamond(current + diamonds);
        resourcesRepository.save(res);

        result.put("success", true);
        result.put("message", "模拟充值成功，钻石已到账");
        result.put("pkgId", request.pkgId());
        result.put("diamonds", diamonds);
        result.put("diamond", res.getDiamond());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    /**
     * 商店购买道具 - 扣除钻石，添加道具到背包。
     */
    @PostMapping("/buy")
    @Transactional
    public ResponseEntity<Map<String, Object>> buy(@RequestBody GameDtos.ShopBuyRequest request) {
        Long playerId = authService.getCurrentPlayer().getId();
        Map<String, Object> result = new LinkedHashMap<>();

        String itemId = normalizeItemId(request.itemId());
        if (itemId == null || itemId.isBlank()) {
            result.put("success", false);
            result.put("message", "道具ID不能为空");
            return ResponseEntity.ok(result);
        }

        // 查找商品定义：后端维护一份与前端 shop.js 一致的价格表
        Integer price = ShopItemPrices.getPrice(itemId);
        if (price == null) {
            result.put("success", false);
            result.put("message", "无效的道具: " + itemId);
            return ResponseEntity.ok(result);
        }

        Resources res = resourcesRepository.findByPlayerId(playerId).orElse(null);
        if (res == null) {
            result.put("success", false);
            result.put("message", "玩家资源数据不存在");
            return ResponseEntity.ok(result);
        }

        int diamond = res.getDiamond() != null ? res.getDiamond() : 0;
        if (diamond < price) {
            result.put("success", false);
            result.put("message", "钻石不足");
            return ResponseEntity.ok(result);
        }

        // 扣钻石
        res.setDiamond(diamond - price);
        resourcesRepository.save(res);

        // 道具入仓
        String itemName = ShopItemPrices.getItemName(itemId);
        Optional<PlayerItem> existing = playerItemRepository.findByPlayerIdAndItemKey(playerId, itemId);
        if (existing.isPresent()) {
            PlayerItem pi = existing.get();
            pi.setCount(pi.getCount() + 1);
            pi.setUpdatedAt(System.currentTimeMillis());
            playerItemRepository.save(pi);
        } else {
            PlayerItem pi = new PlayerItem();
            pi.setPlayerId(playerId);
            pi.setItemKey(itemId);
            pi.setCount(1);
            pi.setUpdatedAt(System.currentTimeMillis());
            playerItemRepository.save(pi);
        }

        result.put("success", true);
        result.put("message", "已购买 " + (itemName != null ? itemName : itemId));
        result.put("itemId", itemId);
        result.put("price", price);
        result.put("diamond", res.getDiamond());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    private static String normalizeItemId(String itemId) {
        if (itemId == null) return null;
        String normalized = itemId.trim();
        if ("resourcePack".equals(normalized) || "resourcePack500W".equals(normalized)) {
            return "resourcePack500w";
        }
        return normalized;
    }

    /**
     * 商品价格表 - 与前端 shop.js SHOP_ITEMS 保持一致。
     * 集中管理避免前后端价格不一致导致的安全问题。
     */
    private static class ShopItemPrices {
        private static final Map<String, Integer> RECHARGE_DIAMONDS = Map.ofEntries(
            Map.entry("p6", 60),
            Map.entry("p30", 330),
            Map.entry("p98", 1080),
            Map.entry("p198", 2230),
            Map.entry("p328", 3780),
            Map.entry("p648", 7680),
            Map.entry("p1280", 15800),
            Map.entry("mk30", 300),
            Map.entry("wk98", 980)
        );

        private static final Map<String, Integer> PRICES = Map.ofEntries(
            // 军官道具
            Map.entry("expBook", 30),
            Map.entry("expBookAdv", 150),
            Map.entry("skillBook", 80),
            Map.entry("loyaltyBox", 50),
            Map.entry("renameCard", 60),
            Map.entry("recruitOrd", 500),
            Map.entry("starUp", 300),
            // 军官装备宝箱
            Map.entry("box_recruit_military", 200),
            Map.entry("box_recruit_logistics", 200),
            Map.entry("box_recruit_knowledge", 200),
            Map.entry("box_officer_military", 1200),
            Map.entry("box_officer_logistics", 1200),
            Map.entry("box_officer_knowledge", 1200),
            Map.entry("box_marshal_military", 5000),
            Map.entry("box_marshal_logistics", 5000),
            Map.entry("box_marshal_knowledge", 5000),
            // 军官套装（27 件）—— 列兵套装 Lv.1+
            Map.entry("recruit_military_weapon", 80),
            Map.entry("recruit_military_badge", 80),
            Map.entry("recruit_military_coat", 80),
            Map.entry("recruit_logistics_weapon", 80),
            Map.entry("recruit_logistics_badge", 80),
            Map.entry("recruit_logistics_coat", 80),
            Map.entry("recruit_knowledge_weapon", 80),
            Map.entry("recruit_knowledge_badge", 80),
            Map.entry("recruit_knowledge_coat", 80),
            // 校官套装 Lv.40+
            Map.entry("officer_military_weapon", 480),
            Map.entry("officer_military_badge", 480),
            Map.entry("officer_military_coat", 480),
            Map.entry("officer_logistics_weapon", 480),
            Map.entry("officer_logistics_badge", 480),
            Map.entry("officer_logistics_coat", 480),
            Map.entry("officer_knowledge_weapon", 480),
            Map.entry("officer_knowledge_badge", 480),
            Map.entry("officer_knowledge_coat", 480),
            // 元帅套装 Lv.100+
            Map.entry("marshal_military_weapon", 2000),
            Map.entry("marshal_military_badge", 2000),
            Map.entry("marshal_military_coat", 2000),
            Map.entry("marshal_logistics_weapon", 2000),
            Map.entry("marshal_logistics_badge", 2000),
            Map.entry("marshal_logistics_coat", 2000),
            Map.entry("marshal_knowledge_weapon", 2000),
            Map.entry("marshal_knowledge_badge", 2000),
            Map.entry("marshal_knowledge_coat", 2000),
            // 资源道具
            Map.entry("goldBox", 80),
            Map.entry("resBox", 120),
            Map.entry("steelPack", 200),
            Map.entry("supplyPack", 350),
            Map.entry("resourcePack500w", 5000),
            // 功能道具 - 加速符（8 种）
            Map.entry("speedUp10m", 30),
            Map.entry("speedUp1h", 100),
            Map.entry("speedUp5h", 400),
            Map.entry("speedUp12h", 800),
            Map.entry("speedUp24h", 1500),
            Map.entry("speedUp36h", 2000),
            Map.entry("speedUp48h", 2500),
            Map.entry("speedUp72h", 3500),
            Map.entry("shield", 200),
            Map.entry("marchOrd", 100),
            Map.entry("cloak", 80),
            Map.entry("populationOrder", 100),
            // 礼包
            Map.entry("newbiePack", 99),
            Map.entry("monthCard", 1500),
            Map.entry("warChest", 888),
            Map.entry("annivPack", 1999)
        );

        private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("expBook", "经验书"),
            Map.entry("expBookAdv", "高级经验书"),
            Map.entry("skillBook", "技能书"),
            Map.entry("loyaltyBox", "忠诚宝箱"),
            Map.entry("renameCard", "改名卡"),
            Map.entry("recruitOrd", "征募令"),
            Map.entry("starUp", "星耀符"),
            // 军官装备宝箱
            Map.entry("box_recruit_military", "列兵军事装备箱"),
            Map.entry("box_recruit_logistics", "列兵后勤装备箱"),
            Map.entry("box_recruit_knowledge", "列兵学识装备箱"),
            Map.entry("box_officer_military", "校官军事装备箱"),
            Map.entry("box_officer_logistics", "校官后勤装备箱"),
            Map.entry("box_officer_knowledge", "校官学识装备箱"),
            Map.entry("box_marshal_military", "元帅军事装备箱"),
            Map.entry("box_marshal_logistics", "元帅后勤装备箱"),
            Map.entry("box_marshal_knowledge", "元帅学识装备箱"),
            // 军官套装
            Map.entry("recruit_military_weapon", "列兵军刀"),
            Map.entry("recruit_military_badge", "列兵臂章"),
            Map.entry("recruit_military_coat", "列兵作训服"),
            Map.entry("recruit_logistics_weapon", "列兵工具包"),
            Map.entry("recruit_logistics_badge", "列兵通行证"),
            Map.entry("recruit_logistics_coat", "列兵工作服"),
            Map.entry("recruit_knowledge_weapon", "列兵笔记本"),
            Map.entry("recruit_knowledge_badge", "列兵学员章"),
            Map.entry("recruit_knowledge_coat", "列兵学员服"),
            Map.entry("officer_military_weapon", "校官军刀"),
            Map.entry("officer_military_badge", "校官勋章"),
            Map.entry("officer_military_coat", "校官军服"),
            Map.entry("officer_logistics_weapon", "校官补给箱"),
            Map.entry("officer_logistics_badge", "校官调度章"),
            Map.entry("officer_logistics_coat", "校官军需服"),
            Map.entry("officer_knowledge_weapon", "校官战术罗盘"),
            Map.entry("officer_knowledge_badge", "校官参谋章"),
            Map.entry("officer_knowledge_coat", "校官参谋服"),
            Map.entry("marshal_military_weapon", "元帅佩剑"),
            Map.entry("marshal_military_badge", "元帅将星"),
            Map.entry("marshal_military_coat", "元帅礼服"),
            Map.entry("marshal_logistics_weapon", "元帅辎重车"),
            Map.entry("marshal_logistics_badge", "元帅军需印"),
            Map.entry("marshal_logistics_coat", "元帅长袍"),
            Map.entry("marshal_knowledge_weapon", "元帅望远镜"),
            Map.entry("marshal_knowledge_badge", "元帅军师印"),
            Map.entry("marshal_knowledge_coat", "元帅军礼服"),
            Map.entry("goldBox", "黄金箱"),
            Map.entry("resBox", "资源箱"),
            Map.entry("steelPack", "钢铁大礼包"),
            Map.entry("supplyPack", "战备补给包"),
            Map.entry("resourcePack500w", "资源大礼包"),
            Map.entry("speedUp10m", "10分加速符"),
            Map.entry("speedUp1h", "1时加速符"),
            Map.entry("speedUp5h", "5时加速符"),
            Map.entry("speedUp12h", "12时加速符"),
            Map.entry("speedUp24h", "24时加速符"),
            Map.entry("speedUp36h", "36时加速符"),
            Map.entry("speedUp48h", "48时加速符"),
            Map.entry("speedUp72h", "72时加速符"),
            Map.entry("shield", "护盾"),
            Map.entry("marchOrd", "行军令"),
            Map.entry("cloak", "反侦察符"),
            Map.entry("populationOrder", "人口动员令"),
            Map.entry("newbiePack", "新手礼包"),
            Map.entry("monthCard", "钻石月卡"),
            Map.entry("warChest", "战备月卡"),
            Map.entry("annivPack", "周年庆大礼")
        );

        private static final java.util.Set<String> GIFTS = java.util.Set.of(
            "newbiePack", "monthCard", "warChest", "annivPack"
        );

        static Integer getRechargeDiamond(String pkgId) { return RECHARGE_DIAMONDS.get(pkgId); }
        static Integer getPrice(String itemId) { return PRICES.get(itemId); }
        static String getItemName(String itemId) { return NAMES.get(itemId); }
    }
}