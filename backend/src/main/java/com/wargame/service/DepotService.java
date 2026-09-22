package com.wargame.service;

import com.wargame.model.constants.GameData;
import com.wargame.model.constants.ItemDef;
import com.wargame.model.constants.OfficerEquipmentDef;
import com.wargame.model.entity.CityState;
import com.wargame.model.entity.Officer;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerItem;
import com.wargame.model.entity.Resources;
import com.wargame.repository.CityStateRepository;
import com.wargame.repository.OfficerRepository;
import com.wargame.repository.BuildingRepository;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.ResourcesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 仓库道具使用服务。
 * <p>
 * 历史上 depot.js 完全在前端修改 Core.state（资源、exp、技能、护盾/行军等），
 * 没有调用任何后端 API，导致刷新页面后所有效果丢失。
 * <p>
 * 本服务对所有"使用即生效"的道具做服务端权威化处理：
 * <ul>
 *   <li>资源类（黄金箱/资源箱/钢铁大礼包/战备补给包）→ 写入 resources 表</li>
 *   <li>军官类（经验书/技能书/忠诚宝箱/改名卡/星耀符）→ 写入 officers 表</li>
 *   <li>征募令 → 在 officers 表新增 1 名 5 星军官</li>
 *   <li>功能类（护盾/行军令）→ 写入 city_state 表</li>
 * </ul>
 * 每次调用都会原子地：
 * 1. 校验玩家持有该道具
 * 2. 扣减 player_items
 * 3. 应用效果到对应表
 * 4. 返回最新 state
 */
@Service
public class DepotService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;

    private final ResourcesRepository resourcesRepository;
    private final PlayerItemRepository playerItemRepository;
    private final OfficerRepository officerRepository;
    private final CityStateRepository cityStateRepository;
    private final PlayerRepository playerRepository;
    private final BuildingRepository buildingRepository;
    private final OfficerService officerService;

    public DepotService(ResourcesRepository resourcesRepository,
                        PlayerItemRepository playerItemRepository,
                        OfficerRepository officerRepository,
                        CityStateRepository cityStateRepository,
                        PlayerRepository playerRepository,
                        BuildingRepository buildingRepository,
                        OfficerService officerService) {
        this.resourcesRepository = resourcesRepository;
        this.playerItemRepository = playerItemRepository;
        this.officerRepository = officerRepository;
        this.cityStateRepository = cityStateRepository;
        this.playerRepository = playerRepository;
        this.buildingRepository = buildingRepository;
        this.officerService = officerService;
    }

    /**
     * 通用道具使用入口。根据 itemId 分发到对应的具体实现。
     *
     * @param playerId   玩家 ID
     * @param itemId     道具 key
     * @param officerId  军官 ID（仅军官类道具需要）
     * @param newName    新名字（仅改名卡需要）
     */
    @Transactional
    public Map<String, Object> useItem(Long playerId, String itemId, Long officerId, String newName) {
        if (itemId == null || itemId.isBlank()) {
            return error("道具ID不能为空");
        }

        // 资源类（不需要选军官）
        if (isResourceItem(itemId)) {
            return useResourceItem(playerId, itemId);
        }
        // 装备宝箱类（开启直接获得整套 3 件装备）
        if (isEquipmentBox(itemId)) {
            return useEquipmentBox(playerId, itemId);
        }
        // 军衔珠宝宝箱类（开启直接获得对应军衔晋升珠宝）
        if (isJewelryBox(itemId)) {
            return useJewelryBox(playerId, itemId);
        }
        // 功能类
        if ("shield".equals(itemId)) return useShield(playerId);
        if ("marchOrd".equals(itemId)) return useMarchOrd(playerId);
        if ("populationOrder".equals(itemId)) return usePopulationOrder(playerId);
        // 征募令
        if ("recruitOrd".equals(itemId)) return useRecruitOrd(playerId);
        // 军官类
        if ("expBook".equals(itemId) || "expBookAdv".equals(itemId) || "expBookMax".equals(itemId)) {
            return useExpBook(playerId, itemId, officerId);
        }
        if ("skillBook".equals(itemId)) {
            return useSkillBook(playerId, officerId);
        }
        String specificSkillId = specificSkillId(itemId);
        if (specificSkillId != null) {
            return useSpecificSkillBook(playerId, itemId, officerId, specificSkillId);
        }
        if ("loyaltyBox".equals(itemId)) {
            return useLoyaltyBox(playerId, officerId);
        }
        if ("renameCard".equals(itemId)) {
            return useRenameCard(playerId, officerId, newName);
        }
        if ("starUp".equals(itemId)) {
            return useStarUp(playerId, officerId);
        }
        return error("不支持的道具: " + itemId);
    }

    // ================================================================
    //  资源类道具
    // ================================================================

    private static boolean isResourceItem(String itemId) {
        return "goldBox".equals(itemId)
                || "resBox".equals(itemId)
                || "steelPack".equals(itemId)
                || "supplyPack".equals(itemId)
                || "resourcePack500w".equals(itemId);
    }

    private Map<String, Object> useResourceItem(Long playerId, String itemId) {
        // 1. 校验持有并原子扣减
        int consumed = playerItemRepository.tryConsume(playerId, itemId, 1, System.currentTimeMillis());
        if (consumed == 0) return error("道具数量不足");

        // 2. 加载资源
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (res == null) {
            // 退还（add back）
            playerItemRepository.tryConsume(playerId, itemId, -1, System.currentTimeMillis());
            return error("玩家资源数据不存在");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        StringBuilder msg = new StringBuilder();
        switch (itemId) {
            case "goldBox" -> {
                // JS: var n = rand(1000, 5000)
                int n = 1000 + ThreadLocalRandom.current().nextInt(4001);
                res.setGold((res.getGold() != null ? res.getGold() : 0) + n);
                msg.append("开启黄金箱,获得 💰").append(n);
            }
            case "resBox" -> {
                res.setFood((res.getFood() != null ? res.getFood() : 0) + 500);
                res.setSteel((res.getSteel() != null ? res.getSteel() : 0) + 500);
                res.setOil((res.getOil() != null ? res.getOil() : 0) + 500);
                res.setRare((res.getRare() != null ? res.getRare() : 0) + 500);
                msg.append("开启资源箱,粮钢油稀各 +500");
            }
            case "steelPack" -> {
                res.setSteel((res.getSteel() != null ? res.getSteel() : 0) + 20000);
                msg.append("获得 钢铁 +20000");
            }
            case "supplyPack" -> {
                res.setFood((res.getFood() != null ? res.getFood() : 0) + 8000);
                res.setSteel((res.getSteel() != null ? res.getSteel() : 0) + 8000);
                res.setOil((res.getOil() != null ? res.getOil() : 0) + 8000);
                res.setRare((res.getRare() != null ? res.getRare() : 0) + 8000);
                msg.append("战备补给包,粮钢油稀各 +8000");
            }
            case "resourcePack500w" -> {
                res.setFood((res.getFood() != null ? res.getFood() : 0) + 5_000_000);
                res.setSteel((res.getSteel() != null ? res.getSteel() : 0) + 5_000_000);
                res.setOil((res.getOil() != null ? res.getOil() : 0) + 5_000_000);
                res.setRare((res.getRare() != null ? res.getRare() : 0) + 5_000_000);
                msg.append("资源大礼包,粮食/钢铁/石油/稀矿各 +500万");
            }
            default -> {
                playerItemRepository.tryConsume(playerId, itemId, -1, System.currentTimeMillis());
                return error("未知资源道具: " + itemId);
            }
        }
        resourcesRepository.save(res);

        result.put("success", true);
        result.put("message", msg.toString());
        result.put("itemId", itemId);
        result.put("resources", Map.of(
                "food", res.getFood() != null ? res.getFood() : 0,
                "steel", res.getSteel() != null ? res.getSteel() : 0,
                "oil", res.getOil() != null ? res.getOil() : 0,
                "rare", res.getRare() != null ? res.getRare() : 0,
                "gold", res.getGold() != null ? res.getGold() : 0
        ));
        return result;
    }

    // ================================================================
    //  功能类道具（写入 city_state）
    // ================================================================

    private CityState getOrCreateCityState(Long playerId) {
        return cityStateRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElseGet(() -> {
            CityState cs = new CityState();
            cs.setPlayerId(playerId);
            cs.setCitySlot(cityScope.slot(playerId));
            cs.setStatus("peace");
            cs.setShieldUntil(0L);
            cs.setPeaceUntil(0L);
            cs.setMarchBoostUntil(0L);
            return cityStateRepository.save(cs);
        });
    }

    private Map<String, Object> useShield(Long playerId) {
        int consumed = playerItemRepository.tryConsume(playerId, "shield", 1, System.currentTimeMillis());
        if (consumed == 0) return error("护盾数量不足");
        CityState cs = getOrCreateCityState(playerId);
        long until = System.currentTimeMillis() + 8L * 3600 * 1000;
        long cur = cs.getShieldUntil() != null ? cs.getShieldUntil() : 0L;
        cs.setShieldUntil(Math.max(cur, until));
        cityStateRepository.save(cs);
        return success("🛡️ 护盾启用,8 小时内免受玩家攻击", Map.of("shieldUntil", cs.getShieldUntil()));
    }

    private Map<String, Object> useMarchOrd(Long playerId) {
        int consumed = playerItemRepository.tryConsume(playerId, "marchOrd", 1, System.currentTimeMillis());
        if (consumed == 0) return error("行军令数量不足");
        CityState cs = getOrCreateCityState(playerId);
        long until = System.currentTimeMillis() + 3600_000L;
        long cur = cs.getMarchBoostUntil() != null ? cs.getMarchBoostUntil() : 0L;
        cs.setMarchBoostUntil(Math.max(cur, until));
        cityStateRepository.save(cs);
        return success("🚩 行军令启用,行军速度 +50% 持续 1 小时", Map.of("marchBoostUntil", cs.getMarchBoostUntil()));
    }

    private Map<String, Object> usePopulationOrder(Long playerId) {
        int consumed = playerItemRepository.tryConsume(playerId, "populationOrder", 1, System.currentTimeMillis());
        if (consumed == 0) return error("人口动员令数量不足");

        Player player = playerRepository.findById(playerId).orElse(null);
        if (player == null) return error("玩家不存在");
        int capacity = buildingRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).stream()
                .filter(b -> "house".equals(b.getType()))
                .mapToInt(b -> b.getLevel() != null ? b.getLevel() : 0)
                .sum() * 100;
        int current = cityScope.economy(playerId).getCivilianPopulation() != null ? cityScope.economy(playerId).getCivilianPopulation() : 0;
        int added = Math.min(500, Math.max(0, capacity - current));
        if (added == 0) {
            playerItemRepository.tryConsume(playerId, "populationOrder", -1, System.currentTimeMillis());
            return error("当前空闲人口已达到上限");
        }
        cityScope.economy(playerId).setCivilianPopulation(current + added);
        playerRepository.save(player);
        cityScope.saveEconomy(playerId);
        return success("👥 人口动员完成,空闲人口 +" + added, Map.of("civilianPopulation", cityScope.economy(playerId).getCivilianPopulation()));
    }

    // ================================================================
    //  征募令：在 officers 表新增 1 名 5 星军官
    // ================================================================

    private Map<String, Object> useRecruitOrd(Long playerId) {
        int consumed = playerItemRepository.tryConsume(playerId, "recruitOrd", 1, System.currentTimeMillis());
        if (consumed == 0) return error("征募令数量不足");

        // 复用 OfficerService.genOfficer 生成 5 星数据
        Map<String, Object> o = officerService.genOfficer(0);
        // genOfficer 内置概率是 0~1 加权，这里需要强制 5 星：重新 roll 到 star=5
        // 简化做法：直接复用星级判定函数。OfficerService 已暴露 genOfficer 公开方法，
        // 但其内部 star 随机不可控，这里走一次循环重 roll 直到 star=5（最多 50 次）
        for (int i = 0; i < 50 && ((Integer) o.get("star")) < 5; i++) {
            o = officerService.genOfficer(5); // 传高 liaison 提升 5 星概率
        }
        int star = (Integer) o.get("star");
        if (star < 5) {
            o.put("star", 5);
            // 重新计算 base
            star = 5;
        }
        // 与 recruit() 一致落库
        Officer officer = new Officer();
        officer.setPlayerId(playerId);
        officer.setCitySlot(cityScope.slot(playerId));
        officer.setName(o.get("name") != null ? o.get("name").toString() : "未知");
        officer.setStar(5);
        officer.setLevel(1);
        officer.setLogistics(getInt(o, "logistics"));
        officer.setMilitary(getInt(o, "military"));
        officer.setKnowledge(getInt(o, "knowledge"));
        officer.setLoyalty(getInt(o, "loyalty"));
        officer.setSalary(getInt(o, "salary"));
        officer.setExp(0L);
        officer.setRole("idle");
        officer.setRecruitAt(System.currentTimeMillis());
        officer.setRewardAt(0L);
        officer.setSkills(o.get("skills") != null ? com.wargame.util.JsonUtil.toJson(o.get("skills")) : "[]");
        officer.setBio(o.get("bio") != null ? o.get("bio").toString() : "");
        officerRepository.save(officer);

        String starStr = "★★★★★";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "征募成功! " + officer.getName() + " " + starStr + " 正式入伍");
        result.put("officerId", officer.getId());
        return result;
    }

    // ================================================================
    //  军官类道具
    // ================================================================

    private Officer requireOfficer(Long playerId, Long officerId) {
        if (officerId == null) return null;
        List<Officer> list = officerRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId));
        for (Officer o : list) {
            if (o.getId().equals(officerId)) return o;
        }
        return null;
    }

    private Map<String, Object> useExpBook(Long playerId, String itemId, Long officerId) {
        Officer officer = requireOfficer(playerId, officerId);
        if (officer == null) return error("请先选择军官");

        int level = officer.getLevel() != null ? officer.getLevel() : 1;
        if (level >= 100) return error("军官已达最高等级 (Lv.100)");

        if ("expBookMax".equals(itemId)) {
            int consumed = playerItemRepository.tryConsume(playerId, itemId, 1, System.currentTimeMillis());
            if (consumed == 0) return error("满级经验书不足");

            int upgraded = 100 - level;
            int pointsGained = upgraded;
            officer.setLevel(100);
            officer.setExp(0L);
            officer.setAttrPoints((officer.getAttrPoints() != null ? officer.getAttrPoints() : 0) + pointsGained);
            officerRepository.save(officer);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("message", "⚡ " + officer.getName() + " 使用满级经验书直升满级 Lv.100！获得 " + pointsGained + " 点可分配属性！");
            result.put("exp", 0L);
            result.put("level", 100);
            return result;
        }

        int consumed = playerItemRepository.tryConsume(playerId, itemId, 1, System.currentTimeMillis());
        if (consumed == 0) return error("经验书不足");

        // 经验书 +10000 / 高级经验书 +100000
        int gain = "expBookAdv".equals(itemId) ? 100000 : 10000;
        long exp = (officer.getExp() != null ? officer.getExp() : 0L) + gain;
        officer.setExp(exp);

        // 升级循环：每级需要 level*200 经验
        while (level < 100 && exp >= (long) level * 200) {
            exp -= (long) level * 200;
            level += 1;
            officer.setAttrPoints((officer.getAttrPoints() != null ? officer.getAttrPoints() : 0) + 1);
        }
        if (level >= 100) exp = 0L;
        officer.setLevel(level);
        officer.setExp(exp);
        officerRepository.save(officer);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", officer.getName() + " 获得 " + gain + " 经验,当前 Lv." + level);
        result.put("exp", exp);
        result.put("level", level);
        return result;
    }

    private Map<String, Object> useSkillBook(Long playerId, Long officerId) {
        // 复用 OfficerService.learnSkill 已有逻辑（已服务端化）
        return officerService.learnSkill(playerId, officerId);
    }

    /**
     * 解析指定技能书对应的技能 ID。
     * 道具必须同时注册在物品定义和军官技能定义中，避免客户端伪造书名学习任意技能。
     *
     * @param itemId 仓库中使用的道具 ID
     * @return 对应的技能 ID；非指定技能书时返回 {@code null}
     */
    private String specificSkillId(String itemId) {
        if (itemId == null || !itemId.startsWith("skillBook_") || !ItemDef.ITEMS.containsKey(itemId)) return null;
        String skillId = itemId.substring("skillBook_".length());
        return GameData.OFFICER_SKILLS.containsKey(skillId) ? skillId : null;
    }

    private Map<String, Object> useSpecificSkillBook(Long playerId, String itemId, Long officerId, String skillId) {
        return officerService.learnSpecificSkill(playerId, officerId, itemId, skillId);
    }

    private Map<String, Object> useLoyaltyBox(Long playerId, Long officerId) {
        Officer officer = requireOfficer(playerId, officerId);
        if (officer == null) return error("请先选择军官");
        int consumed = playerItemRepository.tryConsume(playerId, "loyaltyBox", 1, System.currentTimeMillis());
        if (consumed == 0) return error("忠诚宝箱不足");
        int loyalty = Math.min(100, (officer.getLoyalty() != null ? officer.getLoyalty() : 0) + 20);
        officer.setLoyalty(loyalty);
        officerRepository.save(officer);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", officer.getName() + " 忠诚度 +20,当前 " + loyalty);
        result.put("loyalty", loyalty);
        return result;
    }

    private Map<String, Object> useRenameCard(Long playerId, Long officerId, String newName) {
        Officer officer = requireOfficer(playerId, officerId);
        if (officer == null) return error("请先选择军官");
        if (newName == null || newName.isBlank()) return error("新名字不能为空");
        if (newName.length() > 12) return error("名字不超过 12 字符");
        int consumed = playerItemRepository.tryConsume(playerId, "renameCard", 1, System.currentTimeMillis());
        if (consumed == 0) return error("改名卡不足");
        String old = officer.getName();
        officer.setName(newName);
        officerRepository.save(officer);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "改名成功: " + old + " → " + newName);
        return result;
    }

    private Map<String, Object> useStarUp(Long playerId, Long officerId) {
        Officer officer = requireOfficer(playerId, officerId);
        if (officer == null) return error("请先选择军官");
        int consumed = playerItemRepository.tryConsume(playerId, "starUp", 1, System.currentTimeMillis());
        if (consumed == 0) return error("星耀符不足");
        int star = officer.getStar() != null ? officer.getStar() : 1;
        if (star >= 5) {
            // 退还
            playerItemRepository.tryConsume(playerId, "starUp", -1, System.currentTimeMillis());
            return error("该军官已满星(5★)");
        }
        officer.setStar(star + 1);
        int level = officer.getLevel() != null ? officer.getLevel() : 1;
        int maxAttrAllowed = Math.max(120, 219 - (100 - level)); // 确保升至满级后单项属性不超过 219
        officer.setMilitary(Math.min(maxAttrAllowed, (int) Math.floor((officer.getMilitary() != null ? officer.getMilitary() : 0) * 1.4) + 5));
        officer.setLogistics(Math.min(maxAttrAllowed, (int) Math.floor((officer.getLogistics() != null ? officer.getLogistics() : 0) * 1.3) + 5));
        officer.setKnowledge(Math.min(maxAttrAllowed, (int) Math.floor((officer.getKnowledge() != null ? officer.getKnowledge() : 0) * 1.2) + 5));
        officerRepository.save(officer);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "✨ " + officer.getName() + " 升为 " + officer.getStar() + "★ !");
        result.put("star", officer.getStar());
        return result;
    }

    // ================================================================
    //  装备宝箱：开启后直接向玩家发放整套 3 件装备（武器/徽章/外套）
    // ================================================================

    private record EquipmentBoxDef(String boxName, List<String> equipmentKeys, String setBonusDesc) {}

    private static final Map<String, EquipmentBoxDef> EQUIPMENT_BOXES = Map.of(
        "box_recruit_military", new EquipmentBoxDef("列兵军事装备箱",
            List.of("recruit_military_weapon", "recruit_military_badge", "recruit_military_coat"), "军事+3"),
        "box_recruit_logistics", new EquipmentBoxDef("列兵后勤装备箱",
            List.of("recruit_logistics_weapon", "recruit_logistics_badge", "recruit_logistics_coat"), "后勤+3"),
        "box_recruit_knowledge", new EquipmentBoxDef("列兵学识装备箱",
            List.of("recruit_knowledge_weapon", "recruit_knowledge_badge", "recruit_knowledge_coat"), "学识+3"),
        "box_officer_military", new EquipmentBoxDef("校官军事装备箱",
            List.of("officer_military_weapon", "officer_military_badge", "officer_military_coat"), "军事+15"),
        "box_officer_logistics", new EquipmentBoxDef("校官后勤装备箱",
            List.of("officer_logistics_weapon", "officer_logistics_badge", "officer_logistics_coat"), "后勤+15"),
        "box_officer_knowledge", new EquipmentBoxDef("校官学识装备箱",
            List.of("officer_knowledge_weapon", "officer_knowledge_badge", "officer_knowledge_coat"), "学识+15"),
        "box_marshal_military", new EquipmentBoxDef("元帅军事装备箱",
            List.of("marshal_military_weapon", "marshal_military_badge", "marshal_military_coat"), "军事+30，全属性+5"),
        "box_marshal_logistics", new EquipmentBoxDef("元帅后勤装备箱",
            List.of("marshal_logistics_weapon", "marshal_logistics_badge", "marshal_logistics_coat"), "后勤+30，全属性+5"),
        "box_marshal_knowledge", new EquipmentBoxDef("元帅学识装备箱",
            List.of("marshal_knowledge_weapon", "marshal_knowledge_badge", "marshal_knowledge_coat"), "学识+30，全属性+5")
    );

    private static boolean isEquipmentBox(String itemId) {
        return itemId != null && EQUIPMENT_BOXES.containsKey(itemId);
    }

    private Map<String, Object> useEquipmentBox(Long playerId, String itemId) {
        EquipmentBoxDef boxDef = EQUIPMENT_BOXES.get(itemId);
        if (boxDef == null) {
            return error("未知的装备宝箱");
        }

        // 1. 原子扣减宝箱道具 1 个
        int consumed = playerItemRepository.tryConsume(playerId, itemId, 1, System.currentTimeMillis());
        if (consumed == 0) {
            return error("【" + boxDef.boxName() + "】数量不足");
        }

        // 2. 发放整套 3 件装备到玩家背包
        long now = System.currentTimeMillis();
        List<String> awardedNames = new ArrayList<>();
        for (String eqKey : boxDef.equipmentKeys()) {
            OfficerEquipmentDef eqDef = OfficerEquipmentDef.ITEMS.get(eqKey);
            String name = eqDef != null ? eqDef.name() : eqKey;
            awardedNames.add(name);

            Optional<PlayerItem> existing = playerItemRepository.findByPlayerIdAndItemKey(playerId, eqKey);
            if (existing.isPresent()) {
                PlayerItem pi = existing.get();
                pi.setCount(pi.getCount() + 1);
                pi.setUpdatedAt(now);
                playerItemRepository.save(pi);
            } else {
                PlayerItem pi = new PlayerItem();
                pi.setPlayerId(playerId);
                pi.setItemKey(eqKey);
                pi.setCount(1);
                pi.setUpdatedAt(now);
                playerItemRepository.save(pi);
            }
        }

        String msg = "🎉 开启【" + boxDef.boxName() + "】，获得整套装备："
                + String.join("、", awardedNames)
                + "（穿齐激活 " + boxDef.setBonusDesc() + "）！";
        return success(msg, Map.of("boxId", itemId, "items", boxDef.equipmentKeys()));
    }

    // ================================================================
    //  军衔珠宝宝箱：开启后直接向玩家发放用于提升军衔的各类珠宝
    // ================================================================

    private record JewelryBoxDef(String boxName, Map<String, Integer> gems) {}

    private static final Map<String, JewelryBoxDef> JEWELRY_BOXES = Map.of(
        "box_gem", new JewelryBoxDef("军衔珠宝宝箱", Map.of(
            "gem_pearl", 5, "gem_coral", 3, "gem_glaze", 3, "gem_amber", 2, "gem_agate", 2
        )),
        "box_gem_primary", new JewelryBoxDef("初级珠宝宝箱", Map.of(
            "gem_pearl", 8, "gem_coral", 6, "gem_glaze", 5
        )),
        "box_gem_medium", new JewelryBoxDef("中级珠宝宝箱", Map.of(
            "gem_amber", 8, "gem_agate", 6, "gem_crystal", 5, "gem_jadeite", 2
        )),
        "box_gem_senior", new JewelryBoxDef("高级珠宝宝箱", Map.of(
            "gem_crystal", 8, "gem_jadeite", 8, "gem_jade", 6, "gem_nightpearl", 3
        )),
        "box_gem_supreme", new JewelryBoxDef("特级夜明珠宝箱", Map.of(
            "gem_nightpearl", 8, "gem_jade", 10, "gem_jadeite", 10
        )),
        "box_gem_grand", new JewelryBoxDef("璀璨珠宝全集箱", Map.of(
            "gem_pearl", 5, "gem_coral", 5, "gem_glaze", 5, "gem_amber", 5, "gem_agate", 5,
            "gem_crystal", 5, "gem_jadeite", 5, "gem_jade", 5, "gem_nightpearl", 5
        ))
    );

    private static boolean isJewelryBox(String itemId) {
        return itemId != null && JEWELRY_BOXES.containsKey(itemId);
    }

    private Map<String, Object> useJewelryBox(Long playerId, String itemId) {
        JewelryBoxDef boxDef = JEWELRY_BOXES.get(itemId);
        if (boxDef == null) {
            return error("未知的珠宝宝箱");
        }

        // 1. 原子扣减宝箱道具 1 个
        int consumed = playerItemRepository.tryConsume(playerId, itemId, 1, System.currentTimeMillis());
        if (consumed == 0) {
            return error("【" + boxDef.boxName() + "】数量不足");
        }

        // 2. 发放各类珠宝到玩家背包
        long now = System.currentTimeMillis();
        List<String> awardedNames = new ArrayList<>();
        Map<String, Integer> awardedItems = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> entry : boxDef.gems().entrySet()) {
            String gemKey = entry.getKey();
            int count = entry.getValue();
            var gemDef = com.wargame.model.constants.MilitaryRankDef.GEMS.get(gemKey);
            String name = gemDef != null ? gemDef.name() : gemKey;
            String icon = gemDef != null ? gemDef.icon() : "💎";
            awardedNames.add(icon + name + "×" + count);
            awardedItems.put(gemKey, count);

            Optional<PlayerItem> existing = playerItemRepository.findByPlayerIdAndItemKey(playerId, gemKey);
            if (existing.isPresent()) {
                PlayerItem pi = existing.get();
                pi.setCount(pi.getCount() + count);
                pi.setUpdatedAt(now);
                playerItemRepository.save(pi);
            } else {
                PlayerItem pi = new PlayerItem();
                pi.setPlayerId(playerId);
                pi.setItemKey(gemKey);
                pi.setCount(count);
                pi.setUpdatedAt(now);
                playerItemRepository.save(pi);
            }
        }

        String msg = "🎉 开启【" + boxDef.boxName() + "】，获得军衔晋升珠宝："
                + String.join("、", awardedNames) + "！";
        return success(msg, Map.of("boxId", itemId, "gems", awardedItems));
    }

    // ================================================================
    //  utils
    // ================================================================

    private static int getInt(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v == null) return 0;
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false);
        r.put("message", msg);
        return r;
    }

    private static Map<String, Object> success(String msg, Map<String, Object> extra) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("message", msg);
        if (extra != null) r.putAll(extra);
        return r;
    }
}
