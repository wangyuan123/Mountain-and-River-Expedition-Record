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

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.compliance.AntiAddictionService protection;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.repository.PlayerRepository playerRepository;

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
        // 当前只有模拟发钻接口，生产不提供付费服务，不能伪装成真实支付或绕过额度账本。
        if (protection.enabled()) throw new com.wargame.security.GameAccessException("PAYMENT_DISABLED", "当前未开放充值服务");
        Long playerId = authService.getCurrentPlayer().getId();
        playerRepository.lockById(playerId).orElseThrow();
        Map<String, Object> result = new LinkedHashMap<>();
        Integer diamonds = ShopItemPrices.getRechargeDiamond(request.pkgId());
        if (diamonds == null) {
            result.put("success", false);
            result.put("message", "无效的充值档位");
            return ResponseEntity.ok(result);
        }

        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0).orElse(null);
        if (res == null) {
            result.put("success", false);
            result.put("message", "玩家资源数据不存在");
            return ResponseEntity.ok(result);
        }

        int current = res.getDiamond() != null ? res.getDiamond() : 0;
        res.setDiamond(current + diamonds);
        resourcesRepository.saveAndFlush(res);

        result.put("success", true);
        result.put("message", "模拟充值成功，钻石已到账");
        result.put("pkgId", request.pkgId());
        result.put("diamonds", diamonds);
        result.put("diamond", res.getDiamond());
        result.put("state", gameStateService.getGameState(playerId));
        return ResponseEntity.ok(result);
    }

    /**
     * 商店购买道具：扣除钻石后入仓；新手礼包每个账号限购一次，入仓后自行开启。
     */
    @PostMapping("/buy")
    @Transactional
    public ResponseEntity<Map<String, Object>> buy(@RequestBody GameDtos.ShopBuyRequest request) {
        Long playerId = authService.getCurrentPlayer().getId();
        playerRepository.lockById(playerId).orElseThrow();
        Map<String, Object> result = new LinkedHashMap<>();

        String itemId = normalizeItemId(request.itemId());
        if (itemId == null || itemId.isBlank()) {
            result.put("success", false);
            result.put("message", "道具ID不能为空");
            return ResponseEntity.ok(result);
        }

        int count = request.count();
        if (count < 1 || count > 999) {
            result.put("success", false);
            result.put("message", "购买数量无效");
            return ResponseEntity.ok(result);
        }

        // 查找商品定义：后端维护一份与前端 shop.js 一致的价格表
        Integer price = ShopItemPrices.getPrice(itemId);
        if (price == null) {
            result.put("success", false);
            result.put("message", "无效的道具: " + itemId);
            return ResponseEntity.ok(result);
        }

        // 新手礼包只从商城发放，库存行即购买凭据；开启后数量为零也不恢复购买资格。
        if ("newbiePack".equals(itemId)) {
            if (count > 1) {
                result.put("success", false);
                result.put("message", "新手礼包每个账号限购1份");
                return ResponseEntity.ok(result);
            }
            playerRepository.lockById(playerId).orElseThrow();
            if (playerItemRepository.findByPlayerIdAndItemKey(playerId, itemId).isPresent()) {
                result.put("success", false);
                result.put("message", "新手礼包每个账号限购1份");
                return ResponseEntity.ok(result);
            }
        }

        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0).orElse(null);
        if (res == null) {
            result.put("success", false);
            result.put("message", "玩家资源数据不存在");
            return ResponseEntity.ok(result);
        }

        long totalCostLong = (long) price * count;
        if (totalCostLong > Integer.MAX_VALUE) {
            result.put("success", false);
            result.put("message", "购买总额超出上限");
            return ResponseEntity.ok(result);
        }
        int totalCost = (int) totalCostLong;

        int diamond = res.getDiamond() != null ? res.getDiamond() : 0;
        if (diamond < totalCost) {
            result.put("success", false);
            result.put("message", "钻石不足");
            return ResponseEntity.ok(result);
        }

        // 扣钻石
        res.setDiamond(diamond - totalCost);
        // Detect a stale balance before inserting an item (which may have a unique key).
        resourcesRepository.saveAndFlush(res);

        // 道具入仓
        String itemName = ShopItemPrices.getItemName(itemId);
        Optional<PlayerItem> existing = playerItemRepository.findByPlayerIdAndItemKey(playerId, itemId);
        if (existing.isPresent()) {
            PlayerItem pi = existing.get();
            pi.setCount(pi.getCount() + count);
            pi.setUpdatedAt(System.currentTimeMillis());
            playerItemRepository.save(pi);
        } else {
            PlayerItem pi = new PlayerItem();
            pi.setPlayerId(playerId);
            pi.setItemKey(itemId);
            pi.setCount(count);
            pi.setUpdatedAt(System.currentTimeMillis());
            playerItemRepository.save(pi);
        }

        result.put("success", true);
        result.put("message", count > 1 ? "已购买 " + (itemName != null ? itemName : itemId) + " ×" + count : "已购买 " + (itemName != null ? itemName : itemId));
        result.put("itemId", itemId);
        result.put("price", price);
        result.put("count", count);
        result.put("totalCost", totalCost);
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
            Map.entry("p1280", 15800)
        );

        private static final Map<String, Integer> PRICES = Map.ofEntries(
            // 军官道具
            Map.entry("expBook", 30),
            Map.entry("expBookAdv", 150),
            Map.entry("expBookMax", 1000),
            Map.entry("skillBook", 80),
            Map.entry("universalSkillBook", 200),
            Map.entry("loyaltyBox", 50),
            Map.entry("renameCard", 60),
            Map.entry("cityRenameCard", 60),
            Map.entry("recruitOrd", 500),
            Map.entry("starUp", 300),
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
            Map.entry("shield2h", 60),
            Map.entry("shield", 200),
            Map.entry("shield12h", 280),
            Map.entry("shield24h", 500),
            Map.entry("shield72h", 1350),
            Map.entry("teleportNormal", 100),
            Map.entry("teleportAdvanced", 500),
            Map.entry("marchOrd", 100),
            Map.entry("signalJammingOrder", 150),
            Map.entry("feintOrder", 250),
            Map.entry("populationOrder", 100),
            Map.entry("emergencyPopulationOrder", 100),
            // 礼包
            Map.entry("newbiePack", 99)
        );

        private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("expBook", "经验书"),
            Map.entry("expBookAdv", "高级经验书"),
            Map.entry("expBookMax", "满级经验书"),
            Map.entry("skillBook", "普通技能书"),
            Map.entry("universalSkillBook", "通用技能书"),
            Map.entry("loyaltyBox", "忠诚宝箱"),
            Map.entry("renameCard", "军官改名卡"),
            Map.entry("cityRenameCard", "城市改名卡"),
            Map.entry("recruitOrd", "征募令"),
            Map.entry("starUp", "星耀符"),
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
            Map.entry("shield2h", "2小时护盾"),
            Map.entry("shield", "8小时护盾"),
            Map.entry("shield12h", "12小时护盾"),
            Map.entry("shield24h", "24小时护盾"),
            Map.entry("shield72h", "72小时护盾"),
            Map.entry("teleportNormal", "普通传送"),
            Map.entry("teleportAdvanced", "高级传送"),
            Map.entry("marchOrd", "行军令"),
            Map.entry("signalJammingOrder", "通讯干扰令"),
            Map.entry("feintOrder", "佯动诱敌令"),
            Map.entry("populationOrder", "人口动员令"),
            Map.entry("emergencyPopulationOrder", "紧急人口动员令"),
            Map.entry("newbiePack", "新手礼包")
        );

        static Integer getRechargeDiamond(String pkgId) { return RECHARGE_DIAMONDS.get(pkgId); }
        static Integer getPrice(String itemId) {
            // 下架或未知商品返回空值供购买接口拒绝，避免三元表达式将空价格拆箱。
            if (specificSkillBookName(itemId) != null) return 160;
            Integer boxPrice = com.wargame.model.constants.OfficerEquipmentDef.boxPrice(itemId);
            return boxPrice != null ? boxPrice : PRICES.get(itemId);
        }

        static String getItemName(String itemId) {
            String boxName = com.wargame.model.constants.OfficerEquipmentDef.boxName(itemId);
            if (boxName != null) return boxName;
            String specificBookName = specificSkillBookName(itemId);
            return specificBookName != null ? specificBookName : NAMES.get(itemId);
        }

        /**
         * 返回已定义军官技能对应的指定技能书名称。
         * 价格与名称均由后端技能定义派生，防止前端伪造不存在的技能书商品。
         */
        private static String specificSkillBookName(String itemId) {
            if (itemId == null || !itemId.startsWith("skillBook_")) return null;
            String skillId = itemId.substring("skillBook_".length());
            var skill = com.wargame.model.constants.OfficerSkillDef.OFFICER_SKILLS.get(skillId);
            return skill == null ? null : skill.name() + "技能书";
        }
    }
}
