package com.wargame.controller;

import com.wargame.model.dto.GameDtos;
import com.wargame.model.constants.MilitaryRankDef;
import com.wargame.model.constants.OfficerExperienceRules;
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
 * 商城控制器 - 处理道具购买并拦截尚未开放的充值。
 * 所有操作持久化到数据库，刷新页面不丢失。
 */
@RestController
@RequestMapping("/api/game/shop")
public class ShopController {
    @org.springframework.beans.factory.annotation.Autowired private com.wargame.service.InventoryService inventory;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.repository.PlayerRepository playerRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.AccountService accounts;

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

    /** 所有环境统一关闭充值；保留接口供旧客户端获得明确提示，不触发任何发钻或 VIP 累充。 */
    @PostMapping("/recharge")
    public ResponseEntity<Map<String, Object>> recharge(@RequestBody(required = false) GameDtos.ShopRechargeRequest request) {
        throw new com.wargame.security.GameAccessException("PAYMENT_DISABLED", "暂未开放充值，敬请期待");
    }

    /**
     * 商店购买道具：扣钻入仓；晋升凭证只允许购买尚未授衔且未持有的档位，每次一张。
     */
    @PostMapping("/buy")
    @Transactional
    public ResponseEntity<Map<String, Object>> buy(@RequestBody GameDtos.ShopBuyRequest request) {
        Long playerId = authService.getCurrentPlayer().getId();
        var player = accounts.lockPlayer(playerId);
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

        // 账号锁覆盖库存核验与扣钻，避免重复点击或跨城并发购买再次堆积同档凭证。
        var certificateRank = MilitaryRankDef.certificateRank(itemId);
        Optional<PlayerItem> certificateStock = Optional.empty();
        if (certificateRank != null) {
            // 使用锁定读取，避免 MySQL 可重复读快照漏掉等待期间已提交的购买。
            certificateStock = playerItemRepository.findForPurchase(playerId, itemId);
            String reason = com.wargame.model.constants.ShopRules.unavailable(itemId, count,
                    player.getMilitaryRank(), certificateStock.map(PlayerItem::getCount).orElse(0), certificateStock.isPresent());
            if (!reason.isEmpty()) {
                result.put("success", false);
                result.put("message", reason);
                return ResponseEntity.ok(result);
            }
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

        Resources res = (certificateRank != null ? resourcesRepository.findCityForTreatment(playerId, 0)
                : resourcesRepository.findByPlayerIdAndCitySlot(playerId, 0)).orElse(null);
        if (res == null) {
            result.put("success", false);
            result.put("message", "玩家资源数据不存在");
            return ResponseEntity.ok(result);
        }

        long totalCostLong = com.wargame.model.constants.ShopRules.cost(price, count);
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
        // 入库与扣钻共用外层事务，容量不足时整体回滚。
        inventory.add(playerId, itemId, count);

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
    public static class ShopItemPrices {
        public static final Map<String, Integer> PRICES = Map.ofEntries(
            // 军官道具
            Map.entry("expBook", 10),
            Map.entry("expBookAdv", 100),
            Map.entry("expBookRapid", OfficerExperienceRules.RAPID_BOOK_PRICE),
            Map.entry("expBookMax", OfficerExperienceRules.maxBookPrice()),
            Map.entry("skillBook", 80),
            Map.entry("universalSkillBook", 800),
            Map.entry("loyaltyBox", 50),
            Map.entry("renameCard", 60),
            Map.entry("cityRenameCard", 60),
            Map.entry("recruitOrd", 500),
            Map.entry("starUp", 300),
            // 资源道具
            Map.entry("goldBox", 80),
            Map.entry("resBox", 80),
            Map.entry("foodPack", 200),
            Map.entry("steelPack", 200),
            Map.entry("oilPack", 200),
            Map.entry("rarePack", 200),
            Map.entry("supplyPack", 500),
            Map.entry("resourcePack100w", 1150),
            Map.entry("resourcePack200w", 2200),
            Map.entry("resourcePack350w", 3700),
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
            Map.entry("basicMarchOrd", 100),
            Map.entry("advancedMarchOrd", 250),
            Map.entry("marchOrd", 500),
            Map.entry("signalJammingOrder", 150),
            Map.entry("feintOrder", 250),
            Map.entry("warSpeedOrder", 250),
            Map.entry("populationOrder", 100),
            Map.entry("emergencyPopulationOrder", 100),
            Map.entry("mobilizationOrder", 80),
            Map.entry("advancedMobilizationOrder", 220),
            Map.entry("rapidMobilizationOrder", 500),
            // 礼包
            Map.entry("newbiePack", 99)
        );

        private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("expBook", "经验书"),
            Map.entry("expBookAdv", "高级经验书"),
            Map.entry("expBookRapid", "极速经验书"),
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
            Map.entry("foodPack", "粮食大礼包"),
            Map.entry("steelPack", "钢铁大礼包"),
            Map.entry("oilPack", "石油大礼包"),
            Map.entry("rarePack", "稀矿大礼包"),
            Map.entry("supplyPack", "战备补给包"),
            Map.entry("resourcePack100w", "资源大礼包（100万）"),
            Map.entry("resourcePack200w", "资源大礼包（200万）"),
            Map.entry("resourcePack350w", "资源大礼包（350万）"),
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
            Map.entry("basicMarchOrd", "初级行军令"),
            Map.entry("advancedMarchOrd", "高级行军令"),
            Map.entry("marchOrd", "极速行军令"),
            Map.entry("signalJammingOrder", "通讯干扰令"),
            Map.entry("feintOrder", "撤退拖延令"),
            Map.entry("warSpeedOrder", "宣战加速令"),
            Map.entry("populationOrder", "人口动员令"),
            Map.entry("emergencyPopulationOrder", "紧急人口动员令"),
            Map.entry("mobilizationOrder", "战争动员令"),
            Map.entry("advancedMobilizationOrder", "高级战争动员令"),
            Map.entry("rapidMobilizationOrder", "极速战争动员令"),
            Map.entry("newbiePack", "新手礼包")
        );

        public static Integer getPrice(String itemId) {
            var rank = MilitaryRankDef.certificateRank(itemId);
            if (rank != null) return rank.certificatePrice();
            // 指定技能书已下架，不再动态生成售价；旧客户端的购买请求同样由空价格拒绝。
            Integer boxPrice = com.wargame.model.constants.OfficerEquipmentDef.boxPrice(itemId);
            return boxPrice != null ? boxPrice : PRICES.get(itemId);
        }

        static String getItemName(String itemId) {
            var rank = MilitaryRankDef.certificateRank(itemId);
            if (rank != null) return rank.certificateName();
            String boxName = com.wargame.model.constants.OfficerEquipmentDef.boxName(itemId);
            if (boxName != null) return boxName;
            return NAMES.get(itemId);
        }
    }
}
