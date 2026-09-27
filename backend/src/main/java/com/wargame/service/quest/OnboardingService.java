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
    private final OfficerRepository officers;
    private final ResourcesRepository resources;
    private final ScoutReportRepository reports;
    private final MarchRepository marches;
    private final WildTileRepository wilds;
    private final WorldTerrainService terrain;
    private final CityScope scope;

    public record Objective(String id, String title, String body, String route, String building) {}
    public record Supply(String id, String title, List<String> requires, Map<String, Integer> resources) {}
    public record Reward(Map<String, Integer> resources, int diamond) {}

    public static final List<Objective> OBJECTIVES = List.of(
            new Objective("base", "升级前线指挥部", "先建1级农田、炼钢厂与集结兵舍，再把前线指挥部升到2级。", "buildArmy", "command"),
            new Objective("farm", "建设农田", "把农田总等级提升到2级，建立稳定的粮食收入。", "buildRes", "farm"),
            new Objective("factory", "建造战地兵工厂", "建成1级战地兵工厂，开启步兵和运输单位生产。", "buildArmy", "factory"),
            new Objective("infantry", "生产步兵", "完成一次步兵生产，认识军队生产队列。", "army", "infantry"),
            new Objective("train", "生产卡车", "完成一次卡车生产，为之后的采集运输做准备。", "army", "truck"),
            new Objective("lab", "建造国防研究所", "先建1级军需物资库，再建成1级国防研究所。", "buildArmy", "lab"),
            new Objective("reconTech", "研究侦察技术", "先建1级防空雷达站，再完成1级侦察技术研究。", "tech", "recon_level"),
            new Objective("recon", "生产侦察机", "完成一次侦察机生产，准备执行侦察任务。", "army", "scout"),
            new Objective("officer", "招募军官", "建造陆军讲武堂并招募一名军官，之后侦察、占领和采集时可派遣将领。", "academy", "academy"),
            new Objective("scout", "完成一次侦察", "对推荐资源点完成一次成功侦察，等待侦察机返回城内后继续指引。", "world", ""),
            new Objective("occupy", "占领资源点", "派出部队占领一处资源野地，建立第一处外部补给点。", "world", ""),
            new Objective("report", "阅读战报", "打开并阅读一次获胜的野地战报，核对战果。", "reports", ""),
            new Objective("gather", "运回资源", "从资源点采集资源并等待运输队返城入库。", "world", ""),
            new Objective("develop", "完成一次发展", "采集入库后，完成任意一项建筑升级或新建。", "buildRes", ""),
            new Objective("plan", "选择发展方向", "前进基地已经运转起来，选择接下来优先发展的方向。", "onboarding", "")
    );
    private static final Map<String, Reward> STEP_REWARDS = Map.ofEntries(
            Map.entry("base", reward(Map.of("food", 3000, "steel", 2400, "gold", 100), 10)),
            Map.entry("farm", reward(Map.of("food", 3500, "steel", 1000), 10)),
            Map.entry("factory", reward(Map.of("food", 2200, "steel", 3600, "oil", 1000, "gold", 200), 15)),
            Map.entry("infantry", reward(Map.of("food", 2400, "steel", 1600, "gold", 100), 10)),
            Map.entry("train", reward(Map.of("food", 2400, "steel", 1400, "oil", 400, "gold", 100), 10)),
            Map.entry("lab", reward(Map.of("food", 2000, "steel", 2400, "rare", 300), 15)),
            Map.entry("reconTech", reward(Map.of("steel", 2000, "oil", 600, "rare", 400), 15)),
            Map.entry("recon", reward(Map.of("oil", 1400, "rare", 300, "gold", 200), 15)),
            Map.entry("officer", reward(Map.of("food", 2000, "steel", 1500, "gold", 200), 15)),
            Map.entry("scout", reward(Map.of("food", 2500, "steel", 2000, "oil", 600), 15)),
            Map.entry("occupy", reward(Map.of("food", 4000, "steel", 3000, "oil", 1000, "rare", 400), 20)),
            Map.entry("report", reward(Map.of("food", 2000, "steel", 2000, "gold", 200), 10)),
            Map.entry("gather", reward(Map.of("food", 5000, "steel", 3600, "oil", 1000), 20)),
            Map.entry("develop", reward(Map.of("food", 5000, "steel", 4000, "oil", 800, "rare", 500), 20)),
            Map.entry("plan", reward(Map.of("food", 10000, "steel", 8000, "oil", 2500, "rare", 800, "gold", 1000), 75))
    );
    private static final Reward COMPLETION_REWARD = reward(Map.of("food", 25000, "steel", 20000, "oil", 8000, "rare", 3000, "gold", 3000), 200);
    public static final List<Supply> SUPPLIES = List.of(
            new Supply("base", "整备补给", List.of("base"), Map.of("food", 4000, "steel", 4000, "oil", 1200, "rare", 200, "gold", 500)),
            new Supply("scout", "出征补给", List.of("recon", "scout"), Map.of("food", 3000, "steel", 3000, "oil", 1600, "rare", 300, "gold", 500)),
            new Supply("gather", "发展补给", List.of("occupy", "gather"), Map.of("food", 5000, "steel", 4000, "oil", 2000, "rare", 500, "gold", 1000))
    );

    /** 新注册和游客自动加入；旧账号只在主动开启时加入，绝不重置进度或余额。 */
    @Transactional
    public Map<String, Object> start(Long playerId) {
        players.lockById(playerId).orElseThrow();
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

    /** 事件按真实完成结果计入主城；暂停提示仍然积累成果，初始赠兵不算征兵，招募成功才算获得军官。 */
    @Transactional
    public void onEvent(Long playerId, String event, String key, int delta) {
        if (playerId == null || delta <= 0 || scope.slot(playerId) != 0) return;
        if (!Set.of("ARMY_RECRUIT", "OFFICER_RECRUIT", "GATHER_COMPLETE", "BUILD_UPGRADE_DONE").contains(event)) return;
        Map<String, PlayerGuide> rows = locked(playerId);
        if (!rows.containsKey(ENROLLED) || done(rows, "skipped") || done(rows, "plan")) return;
        if ("ARMY_RECRUIT".equals(event) && Set.of("infantry", "truck", "scout").contains(key == null ? "" : key)) {
            mark(rows, playerId, "trained_" + key);
            completeObjective(rows, playerId, "infantry".equals(key) ? "infantry" : "truck".equals(key) ? "train" : "recon");
        }
        if ("GATHER_COMPLETE".equals(event)) completeObjective(rows, playerId, "gather");
        if ("OFFICER_RECRUIT".equals(event)) completeObjective(rows, playerId, "officer");
        if ("BUILD_UPGRADE_DONE".equals(event)) {
            if (done(rows, "gather")) completeObjective(rows, playerId, "develop");
        }
        evaluate(playerId, rows);
    }

    /** 补给以明确的阶段ID领取；资格、入账和已领取标记在同一事务中提交。 */
    @Transactional
    public Map<String, Object> claim(Long playerId, String supplyId) {
        Supply supply = SUPPLIES.stream().filter(s -> s.id().equals(supplyId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("补给不存在"));
        Map<String, PlayerGuide> rows = locked(playerId);
        requireEnrolled(rows);
        if (!done(rows, "skipped") && !done(rows, "plan")) evaluate(playerId, rows);
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

    /** 跳过只结算尚未领取的补给，已领取标记与余额同事务提交；不伪造行动成果。 */
    @Transactional
    public Map<String, Object> skip(Long playerId) {
        players.lockById(playerId).orElseThrow();
        Map<String, PlayerGuide> rows = locked(playerId);
        if (!rows.containsKey(ENROLLED)) save(rows, playerId, ENROLLED, "active");
        PlayerGuide entry = rows.get(ENROLLED);
        if (!done(rows, "skipped") && !done(rows, "plan")) {
            Resources balance = resources.findByPlayerIdAndCitySlot(playerId, 0).orElseThrow();
            for (Supply supply : SUPPLIES) {
                String claimId = PREFIX + "supply_" + supply.id();
                if (rows.containsKey(claimId)) continue;
                Map<String, Integer> reward = supply.resources();
                balance.setFood(balance.getFood() + reward.get("food"));
                balance.setSteel(balance.getSteel() + reward.get("steel"));
                balance.setOil(balance.getOil() + reward.get("oil"));
                balance.setRare(balance.getRare() + reward.get("rare"));
                balance.setGold(balance.getGold() + reward.get("gold"));
                save(rows, playerId, claimId, "claimed");
            }
            resources.save(balance);
            mark(rows, playerId, "skipped");
        }
        entry.setStatus("paused");
        guides.save(entry);
        return snapshot(playerId, rows);
    }

    @Transactional
    public Map<String, Object> choosePlan(Long playerId, String plan) {
        if (!Set.of("economy", "expansion", "military").contains(plan == null ? "" : plan)) throw new IllegalArgumentException("请选择发展方向");
        Map<String, PlayerGuide> rows = locked(playerId);
        requireEnrolled(rows);
        if (done(rows, "skipped")) throw new IllegalArgumentException("行动已跳过，请继续主线任务");
        evaluate(playerId, rows);
        if (!OBJECTIVES.stream().filter(o -> !"plan".equals(o.id())).allMatch(o -> done(rows, o.id()))) {
            throw new IllegalArgumentException("请先完成前进基地行动");
        }
        save(rows, playerId, PREFIX + "plan", plan);
        grantReward(rows, playerId, "plan", STEP_REWARDS.get("plan"));
        grantReward(rows, playerId, "completion", COMPLETION_REWARD);
        return snapshot(playerId, rows);
    }

    /** 首次失败的恢复支持只发一次；不扣人口，不要求购买道具。 */
    @Transactional
    public Map<String, Object> recover(Long playerId) {
        Map<String, PlayerGuide> rows = locked(playerId);
        requireEnrolled(rows);
        if (done(rows, "skipped")) throw new IllegalArgumentException("行动已跳过，请继续主线任务");
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
        if (!done(rows, "skipped") && !done(rows, "plan")) evaluate(playerId, rows);
        List<Map<String, Object>> objectives = OBJECTIVES.stream().map(o -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", o.id()); item.put("title", o.title()); item.put("body", o.body());
            // 新目标不追溯已毕业账号：展示为豁免，不补发招募奖励，也不重开毕业进度。
            boolean exempt = "officer".equals(o.id()) && done(rows, "plan") && !done(rows, o.id());
            item.put("route", o.route()); item.put("building", o.building()); item.put("complete", done(rows, o.id()) || exempt);
            item.put("exempt", exempt);
            Reward reward = STEP_REWARDS.get(o.id());
            item.put("reward", reward.resources()); item.put("diamond", reward.diamond());
            item.put("rewarded", done(rows, "reward_" + o.id()));
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
        boolean skipped = done(rows, "skipped");
        out.put("done", done(rows, "plan") || skipped); out.put("skipped", skipped);
        out.put("objectives", objectives); out.put("supplies", supplies);
        out.put("completed", objectives.stream().filter(o -> Boolean.TRUE.equals(o.get("complete"))).count());
        out.put("current", skipped || done(rows, "plan") ? null : objectives.stream().filter(o -> !Boolean.TRUE.equals(o.get("complete"))).findFirst().orElse(null));
        out.put("recoveryAvailable", done(rows, "defeat") && !done(rows, "recovery") && !done(rows, "plan") && !skipped);
        out.put("plan", done(rows, "plan") ? rows.get(PREFIX + "plan").getStatus() : "");
        out.put("completionReward", COMPLETION_REWARD.resources());
        out.put("completionDiamond", COMPLETION_REWARD.diamond());
        Resources balance = resources.findByPlayerIdAndCitySlot(playerId, 0).orElseThrow();
        Map<String, Integer> balances = new LinkedHashMap<>();
        balances.put("food", balance.getFood()); balances.put("steel", balance.getSteel());
        balances.put("oil", balance.getOil()); balances.put("rare", balance.getRare());
        balances.put("gold", balance.getGold()); balances.put("diamond", Objects.requireNonNullElse(balance.getDiamond(), 0));
        out.put("balances", balances);
        out.put("checks", checks(playerId, rows));
        // 服务端同步等待状态，刷新页面或重新登录也不能在侦察往返途中提前弹出指引。
        out.put("waitingForScoutReturn", !skipped && !done(rows, "plan") && !done(rows, "scout")
                && marches.findByPlayerIdAndCitySlot(playerId, 0).stream()
                .anyMatch(m -> "scout".equals(m.getAction()) && "wild".equals(m.getTargetKind())));
        return out;
    }

    private Map<String, Boolean> checks(Long playerId, Map<String, PlayerGuide> rows) {
        return Map.of("command", level(playerId, "command") >= 2, "farm", sumLevel(playerId, "farm") >= 2,
                "factory", level(playerId, "factory") >= 1, "lab", level(playerId, "lab") >= 1,
                "academy", level(playerId, "academy") >= 1,
                "reconTech", techs.findByPlayerIdAndType(playerId, "recon_level").stream().anyMatch(t -> t.getLevel() >= 1),
                "infantry", done(rows, "trained_infantry"), "truck", done(rows, "trained_truck"), "scout", done(rows, "trained_scout"));
    }

    /** 按实际成果结算目标；侦察情报已送达时仍须等对应行军返城，才发奖并推进。 */
    private void evaluate(Long playerId, Map<String, PlayerGuide> rows) {
        Map<String, Boolean> c = checks(playerId, rows);
        if (c.get("command")) completeObjective(rows, playerId, "base");
        if (c.get("farm")) completeObjective(rows, playerId, "farm");
        if (c.get("factory")) completeObjective(rows, playerId, "factory");
        if (done(rows, "trained_infantry")) completeObjective(rows, playerId, "infantry");
        if (done(rows, "trained_truck")) completeObjective(rows, playerId, "train");
        if (c.get("lab")) completeObjective(rows, playerId, "lab");
        if (c.get("reconTech")) completeObjective(rows, playerId, "reconTech");
        if (done(rows, "trained_scout")) completeObjective(rows, playerId, "recon");
        // 老账号已拥有主城军官时可直接衔接新步骤，不要求再招募一次。
        if (!officers.findByPlayerIdAndCitySlot(playerId, 0).isEmpty()) completeObjective(rows, playerId, "officer");
        if (wilds.findByOccupiedBy(playerId).stream().anyMatch(this::hasResource)) completeObjective(rows, playerId, "occupy");
        long startedAt = rows.get(ENROLLED).getCompletedAt();
        List<March> activeScouts = done(rows, "scout") ? List.of() : marches.findByPlayerId(playerId).stream()
                .filter(m -> "scout".equals(m.getAction()) && "wild".equals(m.getTargetKind())).toList();
        for (ScoutReport report : reports.findByPlayerIdAndCreatedAtGreaterThanEqual(playerId, startedAt)) {
            var data = JsonUtil.parseTree(report.getData());
            if ("scout".equals(report.getType()) && "wild".equals(data.path("targetKind").asText())
                    && data.path("showCityInfo").asBoolean() && data.path("reconLevel").asInt() >= 1
                    && !data.path("resources").isEmpty() && scoutReturned(report, activeScouts)) {
                completeObjective(rows, playerId, "scout");
            }
            if ("battle".equals(report.getType()) && "wild".equals(data.path("targetType").asText())) {
                if (!data.path("win").asBoolean()) mark(rows, playerId, "defeat");
                if (data.path("wildConquered").asBoolean() && report.getReadAt() != null && report.getReadAt() > 0) completeObjective(rows, playerId, "report");
            }
        }
    }

    /** 行军记录在返城入库时删除；旧报告没有行军 ID 时按野地坐标与报告时间兼容关联。 */
    private boolean scoutReturned(ScoutReport report, List<March> activeScouts) {
        long marchId = JsonUtil.parseTree(report.getData()).path("marchId").asLong();
        return activeScouts.stream().noneMatch(m -> {
            if (marchId > 0) return Objects.equals(m.getId(), marchId);
            Integer x = Boolean.TRUE.equals(m.getReturning()) ? m.getOriginX() : m.getTargetX();
            Integer y = Boolean.TRUE.equals(m.getReturning()) ? m.getOriginY() : m.getTargetY();
            return Objects.equals(report.getTargetX(), x) && Objects.equals(report.getTargetY(), y)
                    && m.getStartAt() != null && report.getCreatedAt() != null && m.getStartAt() <= report.getCreatedAt();
        });
    }

    /** 优先沿用可达目标；附近没有合适目标时仅补建一处低等级资源地。 */
    @Transactional
    public Map<String, Object> target(Long playerId, boolean gather) {
        if (scope.slot(playerId) != 0) throw new IllegalArgumentException("请切回主城执行前进基地行动");
        // 与城市选址使用同一把世界锁，避免推荐补建与他人建城重叠。
        WorldMap world = terrain.lockWorld();
        Map<String, PlayerGuide> rows = locked(playerId);
        requireEnrolled(rows);
        if (done(rows, "skipped")) throw new IllegalArgumentException("行动已跳过，请继续主线任务");
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
    private static Reward reward(Map<String, Integer> resources, int diamond) { return new Reward(resources, diamond); }
    private void completeObjective(Map<String, PlayerGuide> rows, Long playerId, String id) {
        if (!done(rows, id)) mark(rows, playerId, id);
        Reward reward = STEP_REWARDS.get(id);
        if (reward != null) grantReward(rows, playerId, id, reward);
    }
    private void grantReward(Map<String, PlayerGuide> rows, Long playerId, String id, Reward reward) {
        if (reward == null || done(rows, "reward_" + id)) return;
        Resources balance = resources.findByPlayerIdAndCitySlot(playerId, 0).orElseThrow();
        Map<String, Integer> values = reward.resources();
        balance.setFood(balance.getFood() + values.getOrDefault("food", 0));
        balance.setSteel(balance.getSteel() + values.getOrDefault("steel", 0));
        balance.setOil(balance.getOil() + values.getOrDefault("oil", 0));
        balance.setRare(balance.getRare() + values.getOrDefault("rare", 0));
        balance.setGold(balance.getGold() + values.getOrDefault("gold", 0));
        balance.setDiamond(Objects.requireNonNullElse(balance.getDiamond(), 0) + reward.diamond());
        resources.save(balance);
        save(rows, playerId, PREFIX + "reward_" + id, "claimed");
    }
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
