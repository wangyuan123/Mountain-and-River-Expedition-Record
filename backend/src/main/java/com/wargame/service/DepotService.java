package com.wargame.service;

import com.wargame.util.GameClock;
import com.wargame.model.constants.BuildingDef;
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
    @org.springframework.beans.factory.annotation.Autowired private CommanderRenameService commanderRename;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    /** 当前星级 1～4 对应升到下一星的失败率，抽签范围为 0～99。 */
    private static final int[] STAR_UP_FAILURE_PERCENT = {10, 20, 30, 60};

    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.CityScope cityScope;
    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.quest.QuestService questService;
    @org.springframework.beans.factory.annotation.Autowired
    private ChatService chatService;

    @org.springframework.beans.factory.annotation.Autowired private OfficerEquipmentProgressService equipmentProgress;

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
        return useItem(playerId, itemId, officerId, newName, null);
    }

    /** 自选技能通过独立参数传递，其他道具忽略 skillId，保持原有使用效果。 */
    @Transactional
    public Map<String, Object> useItem(Long playerId, String itemId, Long officerId, String newName, String skillId) {
        if (itemId == null || itemId.isBlank()) {
            return error("道具ID不能为空");
        }
        if (com.wargame.model.constants.CommanderRenamePolicy.ITEM_KEY.equals(itemId)) {
            commanderRename.rename(playerId, newName);
            return success("统帅名称已修改，消耗1张统帅改名卡", Map.of());
        }

        // 资源类（不需要选军官）
        if (isResourceItem(itemId)) {
            return useResourceItem(playerId, itemId);
        }
        // 装备宝箱类（开启直接获得整套十二件装备）
        if (isEquipmentBox(itemId)) {
            return useEquipmentBox(playerId, itemId);
        }
        // 主线阶段专属豪华大礼包
        if (isStageGift(itemId)) {
            return useStageGift(playerId, itemId);
        }
        // 功能类
        if (ItemDef.SHIELD_HOURS.containsKey(itemId)) return useShield(playerId, itemId);
        if ("marchOrd".equals(itemId)) return useMarchOrd(playerId);
        if ("populationOrder".equals(itemId) || "emergencyPopulationOrder".equals(itemId)) {
            return usePopulationOrder(playerId, itemId);
        }
        // 征募令
        if ("recruitOrd".equals(itemId)) return useRecruitOrd(playerId);
        // 军官类
        if ("expBook".equals(itemId) || "expBookAdv".equals(itemId) || "expBookMax".equals(itemId)) {
            return useExpBook(playerId, itemId, officerId);
        }
        if ("skillBook".equals(itemId)) {
            return useSkillBook(playerId, officerId);
        }
        if ("universalSkillBook".equals(itemId)) {
            return officerService.learnUniversalSkill(playerId, officerId, skillId);
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
                || "newbiePack".equals(itemId)
                || "resourcePack500w".equals(itemId);
    }

    private Map<String, Object> useResourceItem(Long playerId, String itemId) {
        // 新手礼包与限购检查共用账号锁；开启后保留零库存记录，继续标记该账号已经购买。
        if ("newbiePack".equals(itemId) && playerRepository.lockById(playerId).isEmpty()) return error("玩家不存在");
        // 1. 校验持有并原子扣减
        int consumed = playerItemRepository.tryConsume(playerId, itemId, 1, GameClock.now());
        if (consumed == 0) return error("道具数量不足");

        // 2. 加载资源
        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (res == null) {
            // 退还（add back）
            playerItemRepository.tryConsume(playerId, itemId, -1, GameClock.now());
            return error("玩家资源数据不存在");
        }

        Map<String, Long> before = resourceAmounts(res);
        Map<String, Object> result = new LinkedHashMap<>();
        StringBuilder msg = new StringBuilder();
        switch (itemId) {
            case "goldBox" -> {
                // JS: var n = rand(1000, 5000)
                int n = 1000 + ThreadLocalRandom.current().nextInt(4001);
                res.addResource("gold", n);
                msg.append("开启黄金箱,获得 💰").append(n);
            }
            case "resBox" -> {
                res.addResource("food", 500);
                res.addResource("steel", 500);
                res.addResource("oil", 500);
                res.addResource("rare", 500);
                msg.append("开启资源箱,粮钢油稀各 +500");
            }
            case "steelPack" -> {
                res.addResource("steel", 20000);
                msg.append("获得 钢铁 +20000");
            }
            case "supplyPack" -> {
                res.addResource("food", 8000);
                res.addResource("steel", 8000);
                res.addResource("oil", 8000);
                res.addResource("rare", 8000);
                msg.append("战备补给包,粮钢油稀各 +8000");
            }
            case "resourcePack500w" -> {
                res.addResource("food", 5_000_000);
                res.addResource("steel", 5_000_000);
                res.addResource("oil", 5_000_000);
                res.addResource("rare", 5_000_000);
                msg.append("资源大礼包,粮食/钢铁/石油/稀矿各 +500万");
            }
            case "newbiePack" -> {
                // 固定发放商品明示的五种资源，不按售价或任何倍率计算奖励。
                res.addResource("food", 20000);
                res.addResource("steel", 20000);
                res.addResource("oil", 10000);
                res.addResource("rare", 5000);
                res.addResource("gold", 3000);
                msg.append("开启新手礼包，获得：粮食×20000、钢铁×20000、石油×10000、稀矿×5000、黄金×3000");
            }
            default -> {
                playerItemRepository.tryConsume(playerId, itemId, -1, GameClock.now());
                return error("未知资源道具: " + itemId);
            }
        }
        resourcesRepository.save(res);
        chatService.sendItemOpened(playerId, ItemDef.ITEMS.get(itemId).name(), resourceRewards(res, before));

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

    /** 消耗指定档位护盾，仅保护当前城市；各档未到期时顺延，已到期时从当前时间起算。 */
    private Map<String, Object> useShield(Long playerId, String itemId) {
        // 与敌军出征/抵达使用同一玩家锁，确定开盾与开战的先后并避免并发续盾丢失时长。
        if (playerRepository.lockById(playerId).isEmpty()) return error("玩家不存在");
        int consumed = playerItemRepository.tryConsume(playerId, itemId, 1, GameClock.now());
        if (consumed == 0) return error("护盾数量不足");
        CityState cs = getOrCreateCityState(playerId);
        long now = GameClock.now();
        long cur = cs.getShieldUntil() != null ? cs.getShieldUntil() : 0L;
        int hours = ItemDef.SHIELD_HOURS.get(itemId);
        cs.setShieldUntil(Math.max(cur, now) + hours * 3600L * 1000);
        cityStateRepository.save(cs);
        return success("🛡️ 本城护盾时间已增加 " + hours + " 小时，免受侦查、攻击和宣战", Map.of("shieldUntil", cs.getShieldUntil()));
    }

    private Map<String, Object> useMarchOrd(Long playerId) {
        int consumed = playerItemRepository.tryConsume(playerId, "marchOrd", 1, GameClock.now());
        if (consumed == 0) return error("行军令数量不足");
        CityState cs = getOrCreateCityState(playerId);
        long until = GameClock.now() + 3600_000L;
        long cur = cs.getMarchBoostUntil() != null ? cs.getMarchBoostUntil() : 0L;
        cs.setMarchBoostUntil(Math.max(cur, until));
        cityStateRepository.save(cs);
        return success("🚩 行军令启用,行军速度 +50% 持续 1 小时", Map.of("marchBoostUntil", cs.getMarchBoostUntil()));
    }

    /** 普通动员固定增加 500 人，紧急动员按使用时当前城市的民居标称容量增加 30%，均不突破容量。 */
    private Map<String, Object> usePopulationOrder(Long playerId, String itemId) {
        // 同一玩家的召集串行处理；满员时先返回，避免扣道具后再补回库存。
        if (playerRepository.lockById(playerId).isEmpty()) return error("玩家不存在");
        int capacity = buildingRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).stream()
                .filter(b -> "house".equals(b.getType()))
                .mapToInt(b -> b.getLevel() != null ? b.getLevel() : 0)
                .sum() * BuildingDef.BUILDINGS.get("house").popPer();
        int current = Math.max(0, cityScope.economy(playerId).getCivilianPopulation() != null
                ? cityScope.economy(playerId).getCivilianPopulation() : 0);
        boolean emergency = "emergencyPopulationOrder".equals(itemId);
        // 以总容量而非剩余容量为基数，整数人数向下取整；尾次只填充剩余空位。
        int requested = emergency ? (int) (capacity * 30L / 100) : 500;
        int added = Math.min(requested, Math.max(0, capacity - current));
        if (added == 0) return error("当前空闲人口已达到上限");
        int consumed = playerItemRepository.tryConsume(playerId, itemId, 1, GameClock.now());
        if (consumed == 0) return error(ItemDef.ITEMS.get(itemId).name() + "数量不足");

        // 扣库存会清空持久化上下文，重新获取当前城市经济实体再保存人口。
        cityScope.economy(playerId).setCivilianPopulation(current + added);
        cityScope.saveEconomy(playerId);
        return success((emergency ? "📣 紧急人口动员完成" : "👥 人口动员完成") + ",空闲人口 +" + added,
                Map.of("civilianPopulation", current + added));
    }

    // ================================================================
    //  征募令：在 officers 表新增 1 名 5 星军官
    // ================================================================

    private Map<String, Object> useRecruitOrd(Long playerId) {
        int consumed = playerItemRepository.tryConsume(playerId, "recruitOrd", 1, GameClock.now());
        if (consumed == 0) return error("征募令数量不足");

        // 直接生成 5 星极品军官（名将）
        Map<String, Object> o = officerService.genOfficerWithStar(5);
        int star = (Integer) o.get("star");

        // 与 recruit() 一致落库
        Officer officer = new Officer();
        officer.setPlayerId(playerId);
        officer.setCitySlot(cityScope.slot(playerId));
        officer.setName(o.get("name") != null ? o.get("name").toString() : "未知");
        officer.setStar(5);
        officer.setLevel(1);
        officer.setLogistics(getInt(o, "logistics"));
        officer.setMilitary(getInt(o, "military"));
        officer.setDefense(getInt(o, "defense"));
        officer.setKnowledge(getInt(o, "knowledge"));
        officer.setLoyalty(getInt(o, "loyalty"));
        officer.setSalary(getInt(o, "salary"));
        officer.setExp(0L);
        officer.setRole("idle");
        officer.setRecruitAt(GameClock.now());
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
            int consumed = playerItemRepository.tryConsume(playerId, itemId, 1, GameClock.now());
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

        int consumed = playerItemRepository.tryConsume(playerId, itemId, 1, GameClock.now());
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
        int consumed = playerItemRepository.tryConsume(playerId, "loyaltyBox", 1, GameClock.now());
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
        try {
            newName = com.wargame.model.constants.DisplayNamePolicy.validate(newName, 12, "军官名称");
        } catch (IllegalArgumentException error) {
            return error(error.getMessage());
        }
        // 与军官界面的改名共用同一冷却，失败时不扣改名卡。
        entityManager.refresh(officer, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (newName.equals(officer.getName())) return error("新名称与当前军官名称相同");
        long now = GameClock.now();
        if (NameChangePolicy.nextAllowedAt(officer.getNameRenamedAt(), now) > now)
            return error("这名军官今天已改名，请明日 0:00 后再试（按游戏时间，停服顺延）");
        int consumed = playerItemRepository.tryConsume(playerId, "renameCard", 1, GameClock.now());
        if (consumed == 0) return error("军官改名卡不足");
        String old = officer.getName();
        officer.setName(newName);
        officer.setNameRenamedAt(now);
        officerRepository.save(officer);
        try {
            if (questService != null) questService.onEvent(playerId, "OFFICER_RENAME", null, 1);
        } catch (Exception ignored) {}
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "改名成功: " + old + " → " + newName);
        return result;
    }

    private Map<String, Object> useStarUp(Long playerId, Long officerId) {
        return useStarUp(playerId, officerId, ThreadLocalRandom.current().nextInt(100));
    }

    /**
     * 按当前星级判定升星；失败仍消耗一枚星耀符，仅成功时增加星级与属性。
     * @param roll 0～99 的抽签值；显式传入以验证各档失败率边界
     * @return 请求处理结果，upgraded 区分升星成功与已扣道具的失败结果
     */
    Map<String, Object> useStarUp(Long playerId, Long officerId, int roll) {
        Officer officer = requireOfficer(playerId, officerId);
        if (officer == null) return error("请先选择军官");
        int star = officer.getStar() != null ? officer.getStar() : 1;
        if (star >= 5) return error("该军官已满星(5★)");
        int consumed = playerItemRepository.tryConsume(playerId, "starUp", 1, GameClock.now());
        if (consumed == 0) return error("星耀符不足");
        int failurePercent = STAR_UP_FAILURE_PERCENT[star - 1];
        if (roll < failurePercent) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("upgraded", false);
            result.put("message", "升星失败，已消耗1枚星耀符");
            result.put("star", star);
            return result;
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
        result.put("upgraded", true);
        result.put("message", "✨ " + officer.getName() + " 升为 " + officer.getStar() + "★ !");
        result.put("star", officer.getStar());
        return result;
    }

    // ================================================================
    //  装备宝箱：开启后直接向玩家发放整套十二个部位各一件
    // ================================================================

    private static boolean isEquipmentBox(String itemId) {
        return OfficerEquipmentDef.boxItems(itemId).size() == 12;
    }

    private Map<String, Object> useEquipmentBox(Long playerId, String itemId) {
        var rewards = OfficerEquipmentDef.boxItems(itemId);
        String boxName = OfficerEquipmentDef.boxName(itemId);
        if (rewards.size() != 12) {
            return error("未知的装备宝箱");
        }

        equipmentProgress.lockPlayer(playerId);
        // 扣箱和十二件入库在同一事务中，账号锁避免与并发换装覆盖库存。
        // 1. 原子扣减宝箱道具 1 个
        int consumed = playerItemRepository.tryConsume(playerId, itemId, 1, GameClock.now());
        if (consumed == 0) {
            return error("【" + boxName + "】数量不足");
        }

        // 2. 发放整套十二件装备到玩家背包
        long now = GameClock.now();
        List<String> awardedNames = new ArrayList<>();
        for (String eqKey : rewards.stream().map(OfficerEquipmentDef::key).toList()) {
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

        equipmentProgress.recordCollection(playerId, rewards.stream().map(OfficerEquipmentDef::key).toList());
        equipmentProgress.synchronize(playerId);
        chatService.sendItemOpened(playerId, boxName,
                awardedNames.stream().map(name -> name + "×1").toList());
        String msg = "🎉 开启【" + boxName + "】，获得整套装备："
                + String.join("、", awardedNames)
                + "（穿齐激活同套3/6/9/12件共鸣）！";
        return success(msg, Map.of("boxId", itemId, "items", rewards.stream().map(OfficerEquipmentDef::key).toList()));
    }

    // ================================================================
    //  主线阶段专属大礼包
    // ================================================================

    public record StageGiftReward(
            String name,
            int food, int steel, int oil, int rare,
            int gold, int diamond,
            Map<String, Integer> extraItems
    ) {}

    public static final Map<String, StageGiftReward> STAGE_GIFTS = Map.of(
            "stage_gift_1", new StageGiftReward("新兵奠基礼包",
                    20_000, 20_000, 20_000, 5_000,
                    2_000, 50,
                    Map.of("speedUp10m", 5, "box_recruit_military", 1)),
            "stage_gift_2", new StageGiftReward("拔寨起步礼包",
                    50_000, 50_000, 50_000, 15_000,
                    5_000, 100,
                    Map.of("speedUp1h", 3, "skillBook", 2)),
            "stage_gift_3", new StageGiftReward("整军备战礼包",
                    150_000, 150_000, 150_000, 50_000,
                    12_000, 200,
                    Map.of("speedUp1h", 5, "recruitOrd", 2, "box_officer_military", 1)),
            "stage_gift_4", new StageGiftReward("初露锋芒礼包",
                    500_000, 500_000, 500_000, 150_000,
                    30_000, 500,
                    Map.of("speedUp5h", 3, "shield", 1)),
            "stage_gift_5", new StageGiftReward("百战精锐礼包",
                    1_500_000, 1_500_000, 1_500_000, 500_000,
                    80_000, 1_000,
                    Map.of("speedUp12h", 2, "recruitOrd", 5, "expBookAdv", 3)),
            "stage_gift_6", new StageGiftReward("横扫千军礼包",
                    5_000_000, 5_000_000, 5_000_000, 1_500_000,
                    200_000, 2_000,
                    Map.of("speedUp24h", 2, "expBookAdv", 5)),
            "stage_gift_7", new StageGiftReward("威震四海礼包",
                    15_000_000, 15_000_000, 15_000_000, 5_000_000,
                    500_000, 5_000,
                    Map.of("speedUp48h", 2, "box_marshal_military", 1)),
            "stage_gift_8", new StageGiftReward("三军统帅大礼包",
                    50_000_000, 50_000_000, 50_000_000, 15_000_000,
                    1_500_000, 10_000,
                    Map.of("speedUp72h", 3, "box_marshal_defense", 1, "box_marshal_logistics", 1, "expBookMax", 2))
    );

    public static boolean isStageGift(String itemId) {
        return itemId != null && STAGE_GIFTS.containsKey(itemId);
    }

    private Map<String, Object> useStageGift(Long playerId, String itemId) {
        StageGiftReward gift = STAGE_GIFTS.get(itemId);
        if (gift == null) return error("未知的阶段礼包: " + itemId);

        int consumed = playerItemRepository.tryConsume(playerId, itemId, 1, GameClock.now());
        if (consumed == 0) return error("【" + gift.name() + "】数量不足");

        Resources res = resourcesRepository.findByPlayerIdAndCitySlot(playerId, cityScope.slot(playerId)).orElse(null);
        if (res == null) {
            playerItemRepository.tryConsume(playerId, itemId, -1, GameClock.now());
            return error("玩家资源数据不存在");
        }

        Map<String, Long> before = resourceAmounts(res);
        res.addResource("food", gift.food());
        res.addResource("steel", gift.steel());
        res.addResource("oil", gift.oil());
        res.addResource("rare", gift.rare());
        res.addResource("gold", gift.gold());
        res.setDiamond((res.getDiamond() != null ? res.getDiamond() : 0) + gift.diamond());
        resourcesRepository.save(res);

        long now = GameClock.now();
        List<String> bonusNames = new ArrayList<>();
        if (gift.extraItems() != null) {
            for (Map.Entry<String, Integer> e : gift.extraItems().entrySet()) {
                String itKey = e.getKey();
                int cnt = e.getValue();
                addItemToDepot(playerId, itKey, cnt, now);
                ItemDef def = ItemDef.ITEMS.get(itKey);
                bonusNames.add((def != null ? def.name() : itKey) + "×" + cnt);
            }
        }

        List<String> rewards = resourceRewards(res, before);
        rewards.addAll(bonusNames);
        chatService.sendItemOpened(playerId, gift.name(), rewards);

        StringBuilder msg = new StringBuilder();
        msg.append("🎉 成功开启【").append(gift.name()).append("】！获得：\n");
        msg.append("粮/钢/油各 +").append(gift.food()).append("、稀矿 +").append(gift.rare()).append("\n");
        msg.append("黄金 +").append(gift.gold()).append("、钻石 +").append(gift.diamond()).append("！\n");
        if (!bonusNames.isEmpty()) {
            msg.append("额外附赠：").append(String.join("，", bonusNames));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", msg.toString());
        result.put("itemId", itemId);
        result.put("gold", res.getGold());
        result.put("diamond", res.getDiamond());
        return result;
    }

    /** 记录发奖前后的资源数量，广播实际增量，不重复维护礼包奖励配置。 */
    private Map<String, Long> resourceAmounts(Resources res) {
        Map<String, Long> amounts = new LinkedHashMap<>();
        amounts.put("粮食", res.getFood() == null ? 0L : res.getFood());
        amounts.put("钢铁", res.getSteel() == null ? 0L : res.getSteel());
        amounts.put("石油", res.getOil() == null ? 0L : res.getOil());
        amounts.put("稀矿", res.getRare() == null ? 0L : res.getRare());
        amounts.put("黄金", res.getGold() == null ? 0L : res.getGold());
        amounts.put("钻石", res.getDiamond() == null ? 0L : res.getDiamond().longValue());
        return amounts;
    }

    private List<String> resourceRewards(Resources res, Map<String, Long> before) {
        List<String> rewards = new ArrayList<>();
        resourceAmounts(res).forEach((name, amount) -> {
            long gained = amount - before.get(name);
            if (gained > 0) rewards.add(name + "×" + gained);
        });
        return rewards;
    }

    private void addItemToDepot(Long playerId, String itemKey, int count, long now) {
        if (count <= 0) return;
        Optional<PlayerItem> opt = playerItemRepository.findByPlayerIdAndItemKey(playerId, itemKey);
        if (opt.isPresent()) {
            PlayerItem pi = opt.get();
            pi.setCount(pi.getCount() + count);
            pi.setUpdatedAt(now);
            playerItemRepository.save(pi);
        } else {
            PlayerItem pi = new PlayerItem();
            pi.setPlayerId(playerId);
            pi.setItemKey(itemKey);
            pi.setCount(count);
            pi.setUpdatedAt(now);
            playerItemRepository.save(pi);
        }
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
