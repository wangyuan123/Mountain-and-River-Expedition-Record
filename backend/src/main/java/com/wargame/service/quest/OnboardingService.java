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

/** 建设、研究与地面侦查核心行动与可选玩法探索；完成事实、提示偏好和奖励分别持久化。 */
@Service
@RequiredArgsConstructor
public class OnboardingService {
    @org.springframework.beans.factory.annotation.Autowired private com.wargame.service.ArmyPopulationService populationCosts;
    private static final String PREFIX = "ob3_";
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
    private final GuildMemberRepository guildMembers;
    private final UnitLoadoutRepository loadouts;
    private final WorldTerrainService terrain;
    private final CityScope scope;
    private final com.wargame.service.MailService mail;

    public record Objective(String id, String title, String body, String route, String building) {}
    public record Supply(String id, String title, List<String> requires, Map<String, Integer> resources) {}
    public record Reward(Map<String, Integer> resources, int diamond) {}
    public record Exploration(String id, String title, String body, String route, String building, String hint, Map<String, Integer> reward) {}

    public static final List<Objective> OBJECTIVES = List.of(
            new Objective("base", "建设基地", "完成任意一次建筑建造或升级，认识施工队列。", "buildRes", ""),
            new Objective("infantry", "组建部队", "生产一名步兵，认识征召与生产队列；初始部队已足够参加首战。", "army", "infantry"),
            new Objective("recon_build", "建设侦查生产线", "依次完成市政厅2级、军工厂2级、城防雷达1级与科研中心1级；配套建筑按建设页前置推进。", "buildArmy", "factory"),
            new Objective("recon_research", "研究侦察技术", "在科研中心实际完成侦察技术1级研究，开启地面侦查能力。", "tech", "recon_level"),
            new Objective("recon_unit", "生产侦查装甲车", "在军工厂实际生产1辆侦查装甲车，用于地面侦查。", "army", "recon_car"),
            new Objective("scout", "执行侦察", "使用侦查装甲车侦查陆路可达的推荐野地，取得情报后继续。", "world", ""),
            new Objective("occupy", "完成首次讨伐", "讨伐推荐的低难度野地，体验一次胜利；可以顺便占领资源点。", "world", ""),
            new Objective("report", "查看战果", "打开获胜的野地战报查看战果，随后到「任务」探索更多玩法。", "reports", "")
    );
    // 五类资源同档发放：三次行动各1万、真实毕业各5万；沿用领取回执，已领奖不补发差额。
    private static final Reward COMPLETION_REWARD = new Reward(Map.of("food", 50000, "steel", 50000, "oil", 50000, "rare", 50000, "gold", 50000), 100);
    public static final List<Supply> SUPPLIES = List.of(
            new Supply("base", "建设补给", List.of("base"), Map.of("food", 10000, "steel", 10000, "oil", 10000, "rare", 10000, "gold", 10000)),
            new Supply("scout", "出征补给", List.of("infantry", "scout"), Map.of("food", 10000, "steel", 10000, "oil", 10000, "rare", 10000, "gold", 10000)),
            new Supply("battle", "首战补给", List.of("occupy"), Map.of("food", 10000, "steel", 10000, "oil", 10000, "rare", 10000, "gold", 10000))
    );
    public static final List<Exploration> EXPLORATIONS = List.of(
            new Exploration("gather", "首次采集", "从己方资源地采集并将资源运回城市。", "world", "", "选择己方资源地，派出步兵或运输部队采集。步兵每人基础负重10，卡车每辆500，优先使用基础卡车；采满后需手动收获，再下达部队回城命令；资源返城入库后完成任务。", Map.of("food", 5000, "steel", 3600, "oil", 1000)),
            new Exploration("truck", "生产卡车", "生产一辆卡车，提高资源运输能力。", "army", "truck", "卡车擅长运输，负重较高。选择军队中的卡车，征召一辆并等待生产完成。", Map.of("food", 1000, "steel", 1000)),
            new Exploration("tech", "研究科技", "完成一次科技研究，提升基地能力。", "tech", "", "科研中心建成后，选择一项可研究的科技；研究完成后任务自动更新。", Map.of("steel", 1500, "rare", 200)),
            new Exploration("officer", "招募军官", "招募一名军官，了解将领的作用。", "academy", "academy", "军校建成后刷新候选军官，选择一名招募。将领可以出征，也可以留城任职。", Map.of("gold", 300)),
            new Exploration("equipment", "配置兵种装备", "给一个兵种配置装备，提升作战能力。", "army", "infantry", "打开兵种的配装入口，使用仓库中的兵种装备；缺少装备时可以先查看制造条件。", Map.of("steel", 1500)),
            new Exploration("guild", "加入军团", "加入或创建军团，结识其他指挥官。", "guild", "", "在军团页选择一个军团申请加入，审核通过后完成任务；也可以自行创建军团。", Map.of("gold", 300))
    );

    /** 加入只发一次基础设施与基础卡车；侦查车辆和科技由玩家实际建设、研究和生产。 */
    @Transactional
    public Map<String, Object> start(Long playerId) {
        Map<String, PlayerGuide> rows = locked(playerId);
        if (!rows.containsKey(ENROLLED)) {
            save(rows, playerId, ENROLLED, "active");
            if (level(playerId, "factory") == 0) {
                Building factory = new Building(); factory.setPlayerId(playerId); factory.setCitySlot(0);
                factory.setType("factory"); factory.setLevel(1); factory.setSlot(0); buildings.save(factory);
            }
            // 提供高运力的基础卡车，让可选采集无需先走生产教学链；步兵也可少量采集。
            if (armies.findByPlayerIdAndCitySlotAndType(playerId, 0, "truck").stream().mapToInt(a -> a.getCount()).sum() == 0) addArmy(playerId, "truck", 1);
            mark(rows, playerId, "starter");
        }
        return snapshot(playerId, rows);
    }

    @Transactional
    public Map<String, Object> status(Long playerId) { return snapshot(playerId, locked(playerId)); }

    @Transactional
    public Map<String, Object> pause(Long playerId, boolean paused) {
        Map<String, PlayerGuide> rows = locked(playerId);
        PlayerGuide entry = requireEnrolled(rows);
        entry.setStatus(paused ? "paused" : "active"); guides.save(entry);
        return snapshot(playerId, rows);
    }

    /** 核心目标只统计主城真实完成事件；可选任务在暂停、跳过、毕业后以及分城仍可完成。 */
    @Transactional
    public void onEvent(Long playerId, String event, String key, int delta) {
        if (playerId == null || delta <= 0 || event == null) return;
        if (!Set.of("ARMY_RECRUIT", "OFFICER_RECRUIT", "GATHER_COMPLETE", "BUILD_UPGRADE_DONE", "TECH_LEVEL", "SCOUT_WILD", "WILD_CONQUER").contains(event)) return;
        Map<String, PlayerGuide> rows = locked(playerId);
        if ("GATHER_COMPLETE".equals(event)) mark(rows, playerId, "task_gather");
        if ("OFFICER_RECRUIT".equals(event)) mark(rows, playerId, "task_officer");
        if ("TECH_LEVEL".equals(event)) mark(rows, playerId, "task_tech");
        if ("ARMY_RECRUIT".equals(event) && "truck".equals(key)) mark(rows, playerId, "task_truck");
        if (rows.containsKey(ENROLLED) && !finished(rows) && scope.slot(playerId) == 0) {
            if ("ARMY_RECRUIT".equals(event) && "recon_car".equals(key)) mark(rows, playerId, "recon_unit");
            if ("ARMY_RECRUIT".equals(event) && "infantry".equals(key)) mark(rows, playerId, "infantry");
            if ("BUILD_UPGRADE_DONE".equals(event)) mark(rows, playerId, "base");
        }
        evaluate(playerId, rows);
    }

    /** 阶段补给自动到账；手动接口仍进行条件校验，重复请求不重复发放。 */
    @Transactional
    public Map<String, Object> claim(Long playerId, String supplyId) {
        Supply supply = SUPPLIES.stream().filter(s -> s.id().equals(supplyId)).findFirst().orElseThrow(() -> new IllegalArgumentException("补给不存在"));
        Map<String, PlayerGuide> rows = locked(playerId);
        requireEnrolled(rows); evaluate(playerId, rows);
        if (!done(rows, "supply_" + supply.id()) && !supply.requires().stream().allMatch(id -> done(rows, id))) throw new IllegalArgumentException("尚未达成补给条件");
        grantSupply(rows, playerId, supply);
        return snapshot(playerId, rows);
    }

    /** 跳过结清未领阶段补给，不伪造成果，玩法探索继续开放。 */
    @Transactional
    public Map<String, Object> skip(Long playerId) {
        Map<String, PlayerGuide> rows = locked(playerId);
        if (!rows.containsKey(ENROLLED)) save(rows, playerId, ENROLLED, "active");
        if (!finished(rows)) {
            SUPPLIES.forEach(s -> grantSupply(rows, playerId, s));
            mark(rows, playerId, "skipped");
        }
        rows.get(ENROLLED).setStatus("paused"); guides.save(rows.get(ENROLLED));
        return snapshot(playerId, rows);
    }

    /** 全部真实目标完成后自动毕业；重复确认仅返回状态。 */
    @Transactional
    public Map<String, Object> finish(Long playerId) {
        Map<String, PlayerGuide> rows = locked(playerId);
        requireEnrolled(rows); evaluate(playerId, rows);
        if (!finished(rows)) throw new IllegalArgumentException("请先完成建设、研究与地面侦查核心行动");
        return snapshot(playerId, rows);
    }

    /** 首次战败只补员一次；援军不会冒充生产完成事件。 */
    @Transactional
    public Map<String, Object> recover(Long playerId) {
        Map<String, PlayerGuide> rows = locked(playerId);
        requireEnrolled(rows); evaluate(playerId, rows);
        if (finished(rows)) throw new IllegalArgumentException("行动已结束");
        if (!done(rows, "recovery")) {
            if (!done(rows, "defeat")) throw new IllegalArgumentException("当前无需补员");
            addArmy(playerId, "infantry", 30); addArmy(playerId, "truck", 1);
            grantReward(rows, playerId, "recovery", new Reward(Map.of("food", 3000, "steel", 2000, "oil", 1000, "rare", 200), 0));
            mark(rows, playerId, "recovery");
        }
        return snapshot(playerId, rows);
    }

    /** 记录第一次主动探索，返回是否需要展示轻量提示；点击本身不计入任务完成。 */
    @Transactional
    public Map<String, Object> beginTask(Long playerId, String taskId) {
        exploration(taskId);
        Map<String, PlayerGuide> rows = locked(playerId);
        Map<String, Object> result = snapshot(playerId, rows);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) result.get("optionalTasks");
        boolean locked = tasks.stream().anyMatch(t -> taskId.equals(t.get("id")) && "locked".equals(t.get("status")));
        boolean first = !locked && !done(rows, "hint_" + taskId) && !done(rows, "task_" + taskId);
        if (first) mark(rows, playerId, "hint_" + taskId);
        tasks.stream().filter(t -> taskId.equals(t.get("id"))).forEach(t -> t.put("hintSeen", done(rows, "hint_" + taskId)));
        result.put("showHint", first);
        return result;
    }

    /** 探索奖励与完成事实分别保存；在账号锁内完成资源入账及领取标记。 */
    @Transactional
    public Map<String, Object> claimTask(Long playerId, String taskId) {
        Exploration task = exploration(taskId);
        Map<String, PlayerGuide> rows = locked(playerId); evaluate(playerId, rows);
        if (!done(rows, "task_" + taskId)) throw new IllegalArgumentException("探索任务尚未完成");
        grantReward(rows, playerId, "task_" + taskId, new Reward(task.reward(), 0));
        return snapshot(playerId, rows);
    }

    private Map<String, Object> snapshot(Long playerId, Map<String, PlayerGuide> rows) {
        evaluate(playerId, rows);
        List<Map<String, Object>> objectives = OBJECTIVES.stream().map(o -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", o.id()); item.put("title", o.title()); item.put("body", o.body());
            item.put("route", o.route()); item.put("building", o.building()); item.put("complete", done(rows, o.id()));
            return item;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enrolled", rows.containsKey(ENROLLED));
        out.put("paused", rows.containsKey(ENROLLED) && "paused".equals(rows.get(ENROLLED).getStatus()));
        out.put("done", finished(rows)); out.put("skipped", done(rows, "skipped"));
        out.put("objectives", objectives);
        out.put("completed", objectives.stream().filter(o -> Boolean.TRUE.equals(o.get("complete"))).count());
        out.put("current", !rows.containsKey(ENROLLED) || finished(rows) ? null : objectives.stream().filter(o -> !Boolean.TRUE.equals(o.get("complete"))).findFirst().orElse(null));
        out.put("supplies", SUPPLIES.stream().map(s -> Map.of("id", s.id(), "title", s.title(), "resources", s.resources(),
                "claimed", done(rows, "supply_" + s.id()), "available", s.requires().stream().allMatch(id -> done(rows, id)))).toList());
        out.put("recoveryAvailable", done(rows, "defeat") && !done(rows, "recovery") && !finished(rows));
        out.put("completionReward", COMPLETION_REWARD.resources()); out.put("completionDiamond", COMPLETION_REWARD.diamond());
        out.put("checks", Map.of("factory", level(playerId, "factory") >= 1));
        // 只等待情报送达，返程中的侦察部队不会阻塞首战。
        out.put("scouting", !done(rows, "scout") && marches.findByPlayerIdAndCitySlot(playerId, 0).stream().anyMatch(m -> "scout".equals(m.getAction()) && !Boolean.TRUE.equals(m.getReturning())));
        out.put("optionalTasks", optionalTasks(playerId, rows));
        resources.findByPlayerIdAndCitySlot(playerId, 0).ifPresent(balance -> out.put("balances", Map.of("food", balance.getFood(), "steel", balance.getSteel(),
                "oil", balance.getOil(), "rare", balance.getRare(), "gold", balance.getGold(), "diamond", Objects.requireNonNullElse(balance.getDiamond(), 0))));
        return out;
    }

    /** 报告生成即可推进，胜利不限占领模式；已读战报及全部成果自动发奖毕业。 */
    private void evaluate(Long playerId, Map<String, PlayerGuide> rows) {
        if (!officers.findByPlayerId(playerId).isEmpty()) mark(rows, playerId, "task_officer");
        if (techs.findByPlayerId(playerId).stream().anyMatch(t -> Objects.requireNonNullElse(t.getLevel(), 0) > 0)) mark(rows, playerId, "task_tech");
        if (guildMembers.findByPlayerId(playerId).isPresent()) mark(rows, playerId, "task_guild");
        if (loadouts.findByPlayerId(playerId).stream().anyMatch(l -> !JsonUtil.parseObjMap(l.getSlots()).isEmpty())) mark(rows, playerId, "task_equipment");
        if (!rows.containsKey(ENROLLED) || finished(rows)) return;
        // 旧玩家已侦查的事实保留，不要求重新建设或更换侦查方式。
        if (done(rows, "scout")) {
            mark(rows, playerId, "recon_build"); mark(rows, playerId, "recon_research"); mark(rows, playerId, "recon_unit");
        } else {
            if (level(playerId, "command") >= 2 && level(playerId, "factory") >= 2
                    && level(playerId, "radar") >= 1 && level(playerId, "lab") >= 1) mark(rows, playerId, "recon_build");
            if (techs.findByPlayerIdAndType(playerId, "recon_level").stream().anyMatch(t -> Objects.requireNonNullElse(t.getLevel(), 0) >= 1)) mark(rows, playerId, "recon_research");
            if (armies.findByPlayerIdAndCitySlotAndType(playerId, 0, "recon_car").stream().anyMatch(a -> a.getCount() > 0)) mark(rows, playerId, "recon_unit");
        }
        long startedAt = Objects.requireNonNullElse(rows.get(ENROLLED).getCompletedAt(), 0L);
        // 报名标记与战报入库均使用现实时间；战斗发生时间可因停机落后，不能用于筛选报名后的成果。
        for (ScoutReport report : reports.findByPlayerIdAndPersistedRealAtGreaterThanEqual(playerId, startedAt)) {
            var data = JsonUtil.parseTree(report.getData() != null ? report.getData() : report.getSummaryJson());
            // 战报保留出发城市，分城报告不能推动只在主城开展的核心行动。
            if (data.path("citySlot").asInt(0) != 0 || "defender".equals(data.path("perspective").asText())) continue;
            if ("scout".equals(report.getType()) && "wild".equals(data.path("targetKind").asText()) && data.path("showCityInfo").asBoolean()) mark(rows, playerId, "scout");
            if ("battle".equals(report.getType()) && "wild".equals(data.path("targetType").asText())) {
                if (data.path("win").asBoolean()) {
                    mark(rows, playerId, "occupy");
                    if (report.getReadAt() != null && report.getReadAt() > 0) mark(rows, playerId, "report");
                } else mark(rows, playerId, "defeat");
            }
        }
        SUPPLIES.stream().filter(s -> s.requires().stream().allMatch(id -> done(rows, id))).forEach(s -> grantSupply(rows, playerId, s));
        if (OBJECTIVES.stream().allMatch(o -> done(rows, o.id()))) {
            grantReward(rows, playerId, "completion", COMPLETION_REWARD);
            mark(rows, playerId, "completed");
            // 与真实毕业同事务发信；跳过不经过这里，邮件附件仍需玩家主动领取。
            mail.issueOnboardingCompletionGift(playerId);
        }
    }

    /** 可选任务只根据自身条件解锁，互相不串成必须依次完成的链条。 */
    private List<Map<String, Object>> optionalTasks(Long playerId, Map<String, PlayerGuide> rows) {
        boolean owned = wilds.findByOccupiedBy(playerId).stream().anyMatch(t -> Objects.requireNonNullElse(t.getOccupiedCitySlot(), 0) == 0
                && hasResource(t) && Objects.requireNonNullElse(t.getTotalRes(), 0) > Objects.requireNonNullElse(t.getMined(), 0));
        boolean gathering = marches.findByPlayerId(playerId).stream().anyMatch(m -> "wild_gather".equals(m.getTargetKind()) || "gather".equals(m.getAction()));
        return EXPLORATIONS.stream().map(t -> {
            String reason = switch (t.id()) {
                case "gather" -> owned ? "" : "先占领一处有剩余资源的野地";
                case "truck" -> level(playerId, "factory") >= 1 ? "" : "先建造军工厂";
                case "tech" -> level(playerId, "lab") >= 1 ? "" : "先建造科研中心";
                case "officer" -> level(playerId, "academy") >= 1 ? "" : "先建造军校";
                default -> "";
            };
            boolean complete = done(rows, "task_" + t.id()), claimed = done(rows, "reward_task_" + t.id());
            String status = claimed ? "claimed" : complete ? "completed" : "gather".equals(t.id()) && gathering ? "in_progress" : !reason.isEmpty() ? "locked" : "available";
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", t.id()); item.put("title", t.title()); item.put("body", t.body()); item.put("hint", t.hint());
            item.put("route", t.route()); item.put("building", t.building()); item.put("status", status);
            item.put("unlockHint", reason); item.put("hintSeen", done(rows, "hint_" + t.id())); item.put("reward", t.reward());
            return item;
        }).toList();
    }

    /** 优先沿用可达目标；附近没有合适目标时仅补建一处低等级资源地。 */
    @Transactional
    public Map<String, Object> target(Long playerId, boolean gather) {
        if (scope.slot(playerId) != 0) throw new IllegalArgumentException("请切回主城执行前进基地行动");
        Map<String, PlayerGuide> rows = locked(playerId);
        // 先锁账号再锁世界，与出征保持顺序；世界锁避免补建目标与建城重叠。
        WorldMap world = terrain.lockWorld();
        if (!gather) {
            requireEnrolled(rows);
            if (finished(rows)) throw new IllegalArgumentException("行动已结束，请在地图中自行选择目标");
        }
        Player player = players.findById(playerId).orElseThrow();
        String mask = WorldTerrainService.playableMask(terrain.ensure());
        int size = com.wargame.model.constants.WorldConfig.WORLD_SIZE;
        int source = player.getCityPosY() * size + player.getCityPosX();
        List<WildTile> candidates = new ArrayList<>(wilds.findByWorldId(world.getId()));
        candidates.sort(Comparator.comparingInt(t -> Math.abs(t.getX() - player.getCityPosX()) + Math.abs(t.getY() - player.getCityPosY())));
        for (WildTile tile : candidates) {
            if (!hasResource(tile) || tile.getTotalRes() == null || tile.getTotalRes() <= Objects.requireNonNullElse(tile.getMined(), 0)) continue;
            boolean owned = Boolean.TRUE.equals(tile.getOccupied());
            if (gather ? (!playerId.equals(tile.getOccupiedBy()) || Objects.requireNonNullElse(tile.getOccupiedCitySlot(), 0) != 0) : owned) continue;
            Map<String, Integer> defenders = JsonUtil.parseIntMap(tile.getGarrison());
            if (!gather && (defenders.entrySet().stream().anyMatch(e -> !"infantry".equals(e.getKey()) && e.getValue() > 0)
                    || defenders.getOrDefault("infantry", 0) > 10)) continue;
            List<Integer> path = MarchRouteService.path(mask, List.of(source), List.of(tile.getY() * size + tile.getX()), false);
            if (path.isEmpty() || path.size() > 21) continue;
            return targetView(playerId, tile, path.size() - 1);
        }
        if (gather) throw new IllegalArgumentException("附近没有可采集的己方资源地，请先占领新的补给点");
        if (finished(rows)) throw new IllegalArgumentException("行动已完成，请在地图中自行选择目标");
        long created = rows.keySet().stream().filter(k -> k.startsWith(PREFIX + "target_")).count();
        if (created >= 3) throw new IllegalArgumentException("补给点已补建3次，请在地图寻找可用资源地");
        Set<String> used = terrain.occupiedCoordinates(world.getId());
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        Map<Integer, Integer> distance = new HashMap<>(); queue.add(source); distance.put(source, 0);
        while (!queue.isEmpty()) {
            int pos = queue.remove(), d = distance.get(pos), x = pos % size, y = pos / size;
            if (d >= 3 && !used.contains(x + "," + y)) {
                WildTile tile = new WildTile(); tile.setWorldId(world.getId()); tile.setType("grainfield");
                tile.setX(x); tile.setY(y); tile.setLevel(1); tile.setGarrison(JsonUtil.toJson(Map.of("infantry", 10)));
                tile.setScouted(false); tile.setOccupied(false); tile.setTotalRes(com.wargame.model.constants.WorldConfig.RES_PER_WILD_LEVEL); tile.setMined(0);
                wilds.saveAndFlush(tile);
                mark(rows, playerId, "target_" + tile.getId());
                return targetView(playerId, tile, d);
            }
            if (d >= 20) continue;
            for (int next : WorldTerrainService.neighbors(pos,size)) if (!distance.containsKey(next) && mask.charAt(next) == '0') {
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
    private boolean done(Map<String, PlayerGuide> rows, String id) { return rows.containsKey(PREFIX + id); }
    private boolean finished(Map<String, PlayerGuide> rows) { return done(rows, "completed") || done(rows, "skipped"); }
    private Exploration exploration(String id) { return EXPLORATIONS.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("探索任务不存在")); }
    private void addArmy(Long playerId, String type, int count) {
        populationCosts.grant(playerId, type, count);
        ArmyUnit unit = armies.findByPlayerIdAndCitySlotAndType(playerId, 0, type).stream().findFirst().orElseGet(() -> {
            ArmyUnit a = new ArmyUnit(); a.setPlayerId(playerId); a.setCitySlot(0); a.setType(type); a.setCount(0); return a;
        });
        unit.setCount(unit.getCount() + count); armies.save(unit);
    }
    private void grantSupply(Map<String, PlayerGuide> rows, Long playerId, Supply supply) {
        if (done(rows, "supply_" + supply.id())) return;
        grantReward(rows, playerId, "supply_" + supply.id(), new Reward(supply.resources(), 0));
        mark(rows, playerId, "supply_" + supply.id());
    }
    private void grantReward(Map<String, PlayerGuide> rows, Long playerId, String id, Reward reward) {
        if (done(rows, "reward_" + id)) return;
        Resources balance = resources.findByPlayerIdAndCitySlot(playerId, 0).orElseThrow();
        Map<String, Integer> r = reward.resources();
        balance.addResource("food", r.getOrDefault("food", 0)); balance.addResource("steel", r.getOrDefault("steel", 0));
        balance.addResource("oil", r.getOrDefault("oil", 0)); balance.addResource("rare", r.getOrDefault("rare", 0));
        balance.addResource("gold", r.getOrDefault("gold", 0)); balance.setDiamond(Objects.requireNonNullElse(balance.getDiamond(), 0) + reward.diamond());
        resources.save(balance); mark(rows, playerId, "reward_" + id);
    }
    private PlayerGuide requireEnrolled(Map<String, PlayerGuide> rows) {
        if (!rows.containsKey(ENROLLED)) throw new IllegalArgumentException("请先开启前进基地行动");
        return rows.get(ENROLLED);
    }
    private Map<String, PlayerGuide> locked(Long id) {
        // 账号行一直存在，探索任务在未报名及毕业后也能以同一把锁串行化领取。
        players.lockById(id).orElseThrow();
        Map<String, PlayerGuide> rows = new LinkedHashMap<>(); guides.lockByPlayerId(id).forEach(g -> rows.put(g.getStepId(), g));
        return rows;
    }
    private void mark(Map<String, PlayerGuide> rows, Long playerId, String id) { if (!done(rows, id)) save(rows, playerId, PREFIX + id, "done"); }
    private void save(Map<String, PlayerGuide> rows, Long playerId, String id, String status) {
        PlayerGuide row = rows.getOrDefault(id, new PlayerGuide()); row.setPlayerId(playerId); row.setStepId(id); row.setStatus(status);
        row.setCompletedAt(System.currentTimeMillis()); rows.put(id, guides.save(row));
    }
}
