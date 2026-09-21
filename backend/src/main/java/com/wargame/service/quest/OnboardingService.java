package com.wargame.service.quest;

import com.wargame.model.constants.WildTypeDef;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.CityScope;
import com.wargame.service.MarchRouteService;
import com.wargame.service.WorldTerrainService;
import com.wargame.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 前进基地行动：持久化实际成果，提示偏好与一次性补给资格分别保存。 */
@Service
@RequiredArgsConstructor
public class OnboardingService {
    private static final String PREFIX = "ob2_";
    private static final String ENROLLED = PREFIX + "enrolled";
    private final PlayerGuideRepository guides;
    private final PlayerRepository players;
    private final BuildingRepository buildings;
    private final TechnologyRepository techs;
    private final ArmyUnitRepository armies;
    private final ResourcesRepository resources;
    private final ScoutReportRepository reports;
    private final WildTileRepository wilds;
    private final WorldTerrainService terrain;
    private final CityScope scope;

    public record Objective(String id, String title, String body, String route, String building) {}
    public record Supply(String id, String title, List<String> requires, Map<String, Integer> resources) {}

    public static final List<Objective> OBJECTIVES = List.of(
            new Objective("base", "整备前进基地", "将市政厅升至2级、农田总等级达到2，并建成军工厂。开局即有6支施工队，可同时进行6项建筑工程。", "buildArmy", "command"),
            new Objective("train", "组织小队", "完成一批步兵和一批卡车生产。建议先训练3名步兵、2辆卡车，保留平民维持税收；已有50步兵可承担首战。", "army", ""),
            new Objective("recon", "建立侦察能力", "建成科研中心，升级1级侦察技术，并生产至少1架侦察机。科技直接生效，生产完成后飞机才能出发。", "tech", "lab"),
            new Objective("scout", "查明补给点守军", "对资源野地完成一次成功侦察，获得精确守军情报。行军期间可以继续安排建设。", "world", ""),
            new Objective("occupy", "控制补给点", "根据情报选择部队，征服一处资源野地。首次推荐带上现有步兵，卡车留在城内准备运输。", "world", ""),
            new Objective("report", "核对战果", "阅读一次获胜的野地征服战报，查看损失和占领结果。幸存部队需要返城后才能再次出发。", "reports", ""),
            new Objective("gather", "运回第一批补给", "从城内向自己的资源地派出采集队，带上卡车并等待返城入库。已经驻军的地块也可以就地采集并收获。", "world", ""),
            new Objective("develop", "安排下一轮建设", "补给入库后，自行完成一项建筑升级或新建。可以补足粮食收支，也可以发展钢铁或人口。", "buildRes", ""),
            new Objective("plan", "确定发展方向", "前进基地已能生产、出征和获得补给。选择接下来优先发展的方向。", "onboarding", "")
    );
    public static final List<Supply> SUPPLIES = List.of(
            new Supply("base", "整备补给", List.of("base"), Map.of("food", 1000, "steel", 1000, "oil", 300, "rare", 50, "gold", 200)),
            new Supply("scout", "出征补给", List.of("recon", "scout"), Map.of("food", 500, "steel", 500, "oil", 300, "rare", 50, "gold", 200)),
            new Supply("gather", "发展补给", List.of("occupy", "gather"), Map.of("food", 1000, "steel", 800, "oil", 300, "rare", 100, "gold", 300))
    );

    /** 新注册和游客自动加入；旧账号只在主动开启时加入，绝不重置进度或余额。 */
    @Transactional
    public Map<String, Object> start(Long playerId) {
        Map<String, PlayerGuide> rows = locked(playerId);
        if (!rows.containsKey(ENROLLED)) save(rows, playerId, ENROLLED, "active");
        return snapshot(playerId, rows);
    }

    @Transactional
    public Map<String, Object> status(Long playerId) {
        return snapshot(playerId, locked(playerId));
    }

    @Transactional
    public Map<String, Object> pause(Long playerId, boolean paused) {
        Map<String, PlayerGuide> rows = locked(playerId);
        PlayerGuide entry = requireEnrolled(rows);
        entry.setStatus(paused ? "paused" : "active");
        guides.save(entry);
        return snapshot(playerId, rows);
    }

    /** 事件按真实完成结果计入主城；暂停提示仍然积累成果，初始赠兵不算征兵。 */
    @Transactional
    public void onEvent(Long playerId, String event, String key, int delta) {
        if (playerId == null || delta <= 0 || scope.slot(playerId) != 0) return;
        if (!Set.of("ARMY_RECRUIT", "GATHER_COMPLETE", "BUILD_UPGRADE_DONE").contains(event)) return;
        Map<String, PlayerGuide> rows = locked(playerId);
        if (!rows.containsKey(ENROLLED)) return;
        if ("ARMY_RECRUIT".equals(event) && Set.of("infantry", "truck", "scout").contains(key == null ? "" : key)) {
            mark(rows, playerId, "trained_" + key);
        }
        if ("GATHER_COMPLETE".equals(event)) mark(rows, playerId, "gather");
        if ("BUILD_UPGRADE_DONE".equals(event) && done(rows, "gather")) mark(rows, playerId, "develop");
    }

    /** 补给以明确的阶段ID领取；资格、入账和已领取标记在同一事务中提交。 */
    @Transactional
    public Map<String, Object> claim(Long playerId, String supplyId) {
        Supply supply = SUPPLIES.stream().filter(s -> s.id().equals(supplyId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("补给不存在"));
        Map<String, PlayerGuide> rows = locked(playerId);
        requireEnrolled(rows);
        evaluate(playerId, rows);
        String claimId = PREFIX + "supply_" + supply.id();
        if (!rows.containsKey(claimId)) {
            if (!supply.requires().stream().allMatch(id -> done(rows, id))) throw new IllegalArgumentException("尚未达成补给条件");
            Resources balance = resources.findByPlayerIdAndCitySlot(playerId, 0).orElseThrow();
            Map<String, Integer> r = supply.resources();
            balance.setFood(balance.getFood() + r.get("food"));
            balance.setSteel(balance.getSteel() + r.get("steel"));
            balance.setOil(balance.getOil() + r.get("oil"));
            balance.setRare(balance.getRare() + r.get("rare"));
            balance.setGold(balance.getGold() + r.get("gold"));
            resources.save(balance);
            save(rows, playerId, claimId, "claimed");
        }
        return snapshot(playerId, rows);
    }

    @Transactional
    public Map<String, Object> choosePlan(Long playerId, String plan) {
        if (!Set.of("economy", "expansion", "military").contains(plan == null ? "" : plan)) throw new IllegalArgumentException("请选择发展方向");
        Map<String, PlayerGuide> rows = locked(playerId);
        requireEnrolled(rows);
        evaluate(playerId, rows);
        if (!OBJECTIVES.stream().filter(o -> !"plan".equals(o.id())).allMatch(o -> done(rows, o.id()))) {
            throw new IllegalArgumentException("请先完成前进基地行动");
        }
        save(rows, playerId, PREFIX + "plan", plan);
        return snapshot(playerId, rows);
    }

    /** 首次失败的恢复支持只发一次；不扣人口，不要求购买道具。 */
    @Transactional
    public Map<String, Object> recover(Long playerId) {
        Map<String, PlayerGuide> rows = locked(playerId);
        requireEnrolled(rows);
        evaluate(playerId, rows);
        if (!done(rows, "recovery")) {
            if (!done(rows, "defeat") || done(rows, "plan")) throw new IllegalArgumentException("当前无需补员");
            for (var unit : Map.of("infantry", 30, "scout", 1, "truck", 2).entrySet()) {
                ArmyUnit army = armies.findByPlayerIdAndCitySlotAndType(playerId, 0, unit.getKey()).stream().findFirst().orElseGet(() -> {
                    ArmyUnit a = new ArmyUnit(); a.setPlayerId(playerId); a.setCitySlot(0); a.setType(unit.getKey()); a.setCount(0); return a;
                });
                army.setCount(army.getCount() + unit.getValue());
                armies.save(army);
            }
            mark(rows, playerId, "recovery");
        }
        return snapshot(playerId, rows);
    }

    private Map<String, Object> snapshot(Long playerId, Map<String, PlayerGuide> rows) {
        if (!rows.containsKey(ENROLLED)) return Map.of("enrolled", false, "done", false, "objectives", OBJECTIVES);
        evaluate(playerId, rows);
        List<Map<String, Object>> objectives = OBJECTIVES.stream().map(o -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", o.id()); item.put("title", o.title()); item.put("body", o.body());
            item.put("route", o.route()); item.put("building", o.building()); item.put("complete", done(rows, o.id()));
            return item;
        }).toList();
        List<Map<String, Object>> supplies = SUPPLIES.stream().map(s -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.id()); item.put("title", s.title()); item.put("resources", s.resources());
            item.put("claimed", rows.containsKey(PREFIX + "supply_" + s.id()));
            item.put("available", s.requires().stream().allMatch(id -> done(rows, id)));
            return item;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enrolled", true); out.put("paused", "paused".equals(rows.get(ENROLLED).getStatus()));
        out.put("done", done(rows, "plan")); out.put("objectives", objectives); out.put("supplies", supplies);
        out.put("completed", objectives.stream().filter(o -> Boolean.TRUE.equals(o.get("complete"))).count());
        out.put("current", objectives.stream().filter(o -> !Boolean.TRUE.equals(o.get("complete"))).findFirst().orElse(null));
        out.put("recoveryAvailable", done(rows, "defeat") && !done(rows, "recovery") && !done(rows, "plan"));
        out.put("plan", done(rows, "plan") ? rows.get(PREFIX + "plan").getStatus() : "");
        out.put("checks", checks(playerId, rows));
        return out;
    }

    private Map<String, Boolean> checks(Long playerId, Map<String, PlayerGuide> rows) {
        return Map.of("command", level(playerId, "command") >= 2, "farm", sumLevel(playerId, "farm") >= 2,
                "factory", level(playerId, "factory") >= 1, "lab", level(playerId, "lab") >= 1,
                "reconTech", techs.findByPlayerIdAndType(playerId, "recon_level").stream().anyMatch(t -> t.getLevel() >= 1),
                "infantry", done(rows, "trained_infantry"), "truck", done(rows, "trained_truck"), "scout", done(rows, "trained_scout"));
    }

    private void evaluate(Long playerId, Map<String, PlayerGuide> rows) {
        Map<String, Boolean> c = checks(playerId, rows);
        if (c.get("command") && c.get("farm") && c.get("factory")) mark(rows, playerId, "base");
        if (c.get("infantry") && c.get("truck")) mark(rows, playerId, "train");
        if (c.get("lab") && c.get("reconTech") && c.get("scout")) mark(rows, playerId, "recon");
        if (wilds.findByOccupiedBy(playerId).stream().anyMatch(this::hasResource)) mark(rows, playerId, "occupy");
        long startedAt = rows.get(ENROLLED).getCompletedAt();
        for (ScoutReport report : reports.findByPlayerIdAndCreatedAtGreaterThanEqual(playerId, startedAt)) {
            var data = JsonUtil.parseTree(report.getData());
            if ("scout".equals(report.getType()) && "wild".equals(data.path("targetKind").asText())
                    && data.path("showCityInfo").asBoolean() && data.path("reconLevel").asInt() >= 1
                    && !data.path("resources").isEmpty()) mark(rows, playerId, "scout");
            if ("battle".equals(report.getType()) && "wild".equals(data.path("targetType").asText())) {
                if (!data.path("win").asBoolean()) mark(rows, playerId, "defeat");
                if (data.path("wildConquered").asBoolean() && report.getReadAt() != null && report.getReadAt() > 0) mark(rows, playerId, "report");
            }
        }
    }

    /** 优先沿用可达目标；附近没有合适目标时仅补建一处低等级资源地。 */
    @Transactional
    public Map<String, Object> target(Long playerId, boolean gather) {
        if (scope.slot(playerId) != 0) throw new IllegalArgumentException("请切回主城执行前进基地行动");
        // 与城市选址使用同一把世界锁，避免推荐补建与他人建城重叠。
        WorldMap world = terrain.lockWorld();
        Map<String, PlayerGuide> rows = locked(playerId);
        requireEnrolled(rows);
        Player player = players.findById(playerId).orElseThrow();
        String mask = terrain.ensure();
        int source = player.getCityPosY() * WorldTerrainService.SIZE + player.getCityPosX();
        List<WildTile> candidates = new ArrayList<>(wilds.findByWorldId(world.getId()));
        candidates.sort(Comparator.comparingInt(t -> Math.abs(t.getX() - player.getCityPosX()) + Math.abs(t.getY() - player.getCityPosY())));
        for (WildTile tile : candidates) {
            if (!hasResource(tile) || tile.getTotalRes() == null || tile.getTotalRes() <= Objects.requireNonNullElse(tile.getMined(), 0)) continue;
            boolean owned = Boolean.TRUE.equals(tile.getOccupied());
            if (gather ? !playerId.equals(tile.getOccupiedBy()) : owned) continue;
            Map<String, Integer> defenders = JsonUtil.parseIntMap(tile.getGarrison());
            if (!gather && (defenders.entrySet().stream().anyMatch(e -> !"infantry".equals(e.getKey()) && e.getValue() > 0)
                    || defenders.getOrDefault("infantry", 0) > 10)) continue;
            List<Integer> path = MarchRouteService.path(mask, List.of(source), List.of(tile.getY() * WorldTerrainService.SIZE + tile.getX()), false);
            if (path.isEmpty() || path.size() > 21) continue;
            return targetView(playerId, tile, path.size() - 1);
        }
        if (gather) throw new IllegalArgumentException("附近没有可采集的己方资源地，请先占领新的补给点");
        if (done(rows, "plan")) throw new IllegalArgumentException("行动已完成，请在地图中自行选择目标");
        long created = rows.keySet().stream().filter(k -> k.startsWith(PREFIX + "target_")).count();
        if (created >= 3) throw new IllegalArgumentException("补给点已补建3次，请在地图寻找可用资源地");
        Set<String> used = terrain.occupiedCoordinates(world.getId());
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        Map<Integer, Integer> distance = new HashMap<>(); queue.add(source); distance.put(source, 0);
        while (!queue.isEmpty()) {
            int pos = queue.remove(), d = distance.get(pos), x = pos % WorldTerrainService.SIZE, y = pos / WorldTerrainService.SIZE;
            if (d >= 3 && !used.contains(x + "," + y)) {
                WildTile tile = new WildTile(); tile.setWorldId(world.getId()); tile.setType("grainfield");
                tile.setX(x); tile.setY(y); tile.setLevel(1); tile.setGarrison(JsonUtil.toJson(Map.of("infantry", 10)));
                tile.setScouted(false); tile.setOccupied(false); tile.setTotalRes(800); tile.setMined(0);
                wilds.saveAndFlush(tile);
                mark(rows, playerId, "target_" + tile.getId());
                return targetView(playerId, tile, d);
            }
            if (d >= 20) continue;
            for (int next : WorldTerrainService.neighbors(pos)) if (!distance.containsKey(next) && mask.charAt(next) == '0') {
                distance.put(next, d + 1); queue.add(next);
            }
        }
        throw new IllegalArgumentException("附近暂时没有可用陆地，请稍后重新寻找目标");
    }

    private Map<String, Object> targetView(Long playerId, WildTile tile, int distance) {
        Map<String, Object> out = new LinkedHashMap<>(Map.of("id", tile.getId(), "kind", "wild", "type", tile.getType(), "x", tile.getX(), "y", tile.getY(),
                "level", Objects.requireNonNullElse(tile.getLevel(), 1), "occupied", Boolean.TRUE.equals(tile.getOccupied()),
                "occupiedBy", Objects.requireNonNullElse(tile.getOccupiedBy(), 0L), "distance", distance,
                "name", WildTypeDef.WILD_TYPES.get(tile.getType()).name()));
        out.put("totalRes", tile.getTotalRes()); out.put("mined", Objects.requireNonNullElse(tile.getMined(), 0));
        out.put("scouted", false); out.put("garrison", Map.of());
        // 推荐目标不能绕过侦察：只显示该玩家最近一次成功报告中的守军快照。
        for (ScoutReport report : reports.findByPlayerIdOrderByCreatedAtDesc(playerId)) {
            if (!Objects.equals(report.getTargetX(), tile.getX()) || !Objects.equals(report.getTargetY(), tile.getY()) || !"scout".equals(report.getType())) continue;
            Map<String, Object> data = JsonUtil.parseObjMap(report.getData());
            if (Boolean.TRUE.equals(data.get("showCityInfo")) && data.get("army") instanceof Map) {
                out.put("scouted", true); out.put("garrison", data.get("army")); break;
            }
        }
        return out;
    }

    private boolean hasResource(WildTile tile) {
        WildTypeDef def = WildTypeDef.WILD_TYPES.get(tile.getType());
        return def != null && def.res() != null;
    }
    private int level(Long id, String type) { return buildings.findByPlayerIdAndCitySlotAndType(id, 0, type).stream().mapToInt(b -> b.getLevel()).max().orElse(0); }
    private int sumLevel(Long id, String type) { return buildings.findByPlayerIdAndCitySlotAndType(id, 0, type).stream().mapToInt(b -> b.getLevel()).sum(); }
    private boolean done(Map<String, PlayerGuide> rows, String id) { return rows.containsKey(PREFIX + id); }
    private PlayerGuide requireEnrolled(Map<String, PlayerGuide> rows) {
        if (!rows.containsKey(ENROLLED)) throw new IllegalArgumentException("请先开启前进基地行动");
        return rows.get(ENROLLED);
    }
    private Map<String, PlayerGuide> locked(Long id) {
        // 先锁稳定的报名行，再查询成果；否则并发等待前的查询可能漏掉刚插入的领奖记录。
        guides.lockEntry(id, ENROLLED);
        Map<String, PlayerGuide> rows = new LinkedHashMap<>();
        guides.lockByPlayerId(id).forEach(g -> rows.put(g.getStepId(), g));
        return rows;
    }
    private void mark(Map<String, PlayerGuide> rows, Long playerId, String id) {
        if (!done(rows, id)) save(rows, playerId, PREFIX + id, "done");
    }
    private void save(Map<String, PlayerGuide> rows, Long playerId, String id, String status) {
        PlayerGuide row = rows.getOrDefault(id, new PlayerGuide());
        row.setPlayerId(playerId); row.setStepId(id); row.setStatus(status); row.setCompletedAt(System.currentTimeMillis());
        rows.put(id, guides.save(row));
    }
}
