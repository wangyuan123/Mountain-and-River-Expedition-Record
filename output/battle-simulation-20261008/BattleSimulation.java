import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.constants.BattleRules;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.dto.BattleResult;
import com.wargame.service.BattleService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** 用真实引擎比较倍率；A/B 固定表示阵容身份，reverse 仅交换攻守。 */
public class BattleSimulation {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int SEEDS = 32;
    private static final int BUDGET = 56000;
    private static final Pattern ROUND = Pattern.compile("-- 第(\\d+)回合");
    private static final Pattern DISTANCE = Pattern.compile("战场初始距离: (\\d+)");
    private record Scenario(String id, String label, String budget,
                            Map<String, Integer> a, Map<String, Integer> b, boolean affected) {}

    public static void main(String[] args) throws Exception {
        String version = args[0];
        Path output = Path.of(args[1]);
        Files.createDirectories(output.resolve("reports").resolve(version));
        // 验证进程实际加载的倍率，避免 classpath 顺序错误导致伪对照。
        double expectedRocket = version.equals("old") ? 5.5 : 3.0;
        double expectedAA = version.equals("old") ? 5.0 : 2.5;
        for (String id : List.of("htank", "ltank", "armored", "assault"))
            require(BattleRules.multiplier("rocket", id) == expectedRocket, "rocket multiplier");
        for (String id : List.of("fighter", "bomber"))
            require(BattleRules.multiplier("armored", id) == expectedAA, "AA multiplier");

        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> summaries = new ArrayList<>();
        List<Map<String, Object>> representatives = new ArrayList<>();
        for (Scenario scenario : scenarios()) {
            for (boolean developed : List.of(false, true)) {
                String development = developed ? "tech5_military50" : "base";
                List<Map<String, Object>> group = new ArrayList<>();
                for (int seed = 0; seed < SEEDS; seed++) {
                    for (boolean reverse : List.of(false, true)) {
                        BattleResult result = battle(scenario, developed, seed, reverse);
                        validate(result, scenario, reverse);
                        Map<String, Integer> aSurv = reverse ? result.getSurvivorDefender() : result.getSurvivorAttacker();
                        Map<String, Integer> bSurv = reverse ? result.getSurvivorAttacker() : result.getSurvivorDefender();
                        int rounds = lastRound(result.getReport());
                        int firstCombat = new BattleService(seed).firstCombatRoundFromLog(result.getReport());
                        int initialDistance = distance(result.getReport());
                        // 双方仍存活的 30 回合撤退单列，绝不把资源优势当成全歼胜利。
                        String outcome = aSurv.isEmpty() && bSurv.isEmpty() ? "mutual_elimination"
                                : bSurv.isEmpty() ? "A_eliminates_B"
                                : aSurv.isEmpty() ? "B_eliminates_A" : "timeout";
                        if (outcome.equals("timeout")) require(rounds == 30 && !result.isWin(), "timeout semantics");
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("version", version); row.put("scenario", scenario.id());
                        row.put("development", development); row.put("seed", seed);
                        row.put("a_role", reverse ? "defender" : "attacker");
                        row.put("outcome", outcome); row.put("attacker_win", result.isWin());
                        row.put("rounds", rounds); row.put("first_combat_round", firstCombat);
                        row.put("combat_span", firstCombat == 0 ? 0 : rounds - firstCombat + 1);
                        row.put("initial_distance", initialDistance);
                        row.put("a_initial_count", count(scenario.a())); row.put("b_initial_count", count(scenario.b()));
                        row.put("a_survivor_count", count(aSurv)); row.put("b_survivor_count", count(bSurv));
                        row.put("a_losses", count(scenario.a()) - count(aSurv));
                        row.put("b_losses", count(scenario.b()) - count(bSurv));
                        row.put("a_raw_cost", cost(scenario.a(), false)); row.put("b_raw_cost", cost(scenario.b(), false));
                        row.put("a_raw_loss_fraction", loss(scenario.a(), aSurv, false));
                        row.put("b_raw_loss_fraction", loss(scenario.b(), bSurv, false));
                        row.put("a_weighted_loss_fraction", loss(scenario.a(), aSurv, true));
                        row.put("b_weighted_loss_fraction", loss(scenario.b(), bSurv, true));
                        row.put("a_survivors", aSurv); row.put("b_survivors", bSurv);
                        rows.add(row); group.add(row);
                        if (seed == 0) {
                            require(result.equals(battle(scenario, developed, seed, reverse)), "seed reproducibility");
                            var timeline = timeline(scenario, developed, seed, reverse, initialDistance, result);
                            String reportName = version + "/" + scenario.id() + "-" + development + "-a-"
                                    + (reverse ? "defender" : "attacker") + ".txt";
                            // BattleRules.VERSION 是编译期常量；旧倍率进程的日志可能仍显示当前版本。
                            // 以报告首行的显式配置和进程倍率断言为准，不以内联版本串识别基线。
                            String prefix = "模拟配置：" + version + "；反坦克火箭车反装甲×" + expectedRocket
                                    + "；防空装甲车对空×" + expectedAA + "\n"
                                    + "场景：" + scenario.label() + "；成长：" + development
                                    + "；固定种子：0；A 为" + (reverse ? "守方" : "攻方") + "\n"
                                    + "A 初始：" + JSON.writeValueAsString(scenario.a()) + "\n"
                                    + "B 初始：" + JSON.writeValueAsString(scenario.b()) + "\n";
                            Files.writeString(output.resolve("reports").resolve(reportName), prefix + result.getReport(), StandardCharsets.UTF_8);
                            Map<String, Object> representative = new LinkedHashMap<>(row);
                            representative.put("report", "reports/" + reportName);
                            representative.put("timeline", timeline);
                            representatives.add(representative);
                        }
                    }
                }
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("version", version); summary.put("scenario", scenario.id());
                summary.put("label", scenario.label()); summary.put("budget", scenario.budget());
                summary.put("affected", scenario.affected()); summary.put("development", development);
                summary.put("a_initial", scenario.a()); summary.put("b_initial", scenario.b());
                summary.put("a_raw_cost", cost(scenario.a(), false)); summary.put("b_raw_cost", cost(scenario.b(), false));
                summary.put("a_weighted_cost", cost(scenario.a(), true)); summary.put("b_weighted_cost", cost(scenario.b(), true));
                summary.put("runs", group.size());
                for (String outcome : List.of("A_eliminates_B", "B_eliminates_A", "timeout", "mutual_elimination"))
                    summary.put(outcome, group.stream().filter(r -> r.get("outcome").equals(outcome)).count());
                for (String metric : List.of("rounds", "first_combat_round", "combat_span", "a_survivor_count", "b_survivor_count",
                        "a_losses", "b_losses", "a_raw_loss_fraction", "b_raw_loss_fraction", "a_weighted_loss_fraction", "b_weighted_loss_fraction")) {
                    DoubleSummaryStatistics stats = group.stream().mapToDouble(r -> ((Number) r.get(metric)).doubleValue()).summaryStatistics();
                    summary.put(metric + "_mean", stats.getAverage());
                    summary.put(metric + "_min", stats.getMin()); summary.put(metric + "_max", stats.getMax());
                }
                summaries.add(summary);
            }
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve(version + "-runs.json").toFile(), rows);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve(version + "-summary.json").toFile(), summaries);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve(version + "-representatives.json").toFile(), representatives);
        System.out.println(version + ": " + rows.size() + " battles; " + summaries.size() + " groups; "
                + representatives.size() + " replay/parity checks passed");
    }

    private static List<Scenario> scenarios() {
        List<Scenario> scenarios = new ArrayList<>();
        String[][] pairs = {{"rocket", "htank", "反坦克火箭车 vs 重型坦克"}, {"rocket", "ltank", "反坦克火箭车 vs 轻型坦克"},
                {"rocket", "armored", "反坦克火箭车 vs 防空装甲车"}, {"rocket", "assault", "反坦克火箭车 vs 突击炮"},
                {"armored", "fighter", "防空装甲车 vs 战斗机"}, {"armored", "bomber", "防空装甲车 vs 轰炸机"},
                {"infantry", "motor", "对照：步兵 vs 摩托兵"}};
        for (String[] pair : pairs) for (String budget : List.of("COUNT", "COST", "WEIGHTED_COST")) {
            scenarios.add(new Scenario(pair[0] + "-" + pair[1] + "-" + budget.toLowerCase(Locale.ROOT), pair[2], budget,
                    Map.of(pair[0], budgetCount(pair[0], budget)), Map.of(pair[1], budgetCount(pair[1], budget)), !pair[0].equals("infantry")));
        }
        // 按预算份额分别向下取整；明确显示实际资源投入，避免把不同阵容的总数量误称等强度。
        scenarios.add(new Scenario("mixed-ground", "混编：反坦克火箭车支援 vs 装甲突击", "MIXED_COST",
                allocate(Map.of("rocket", 40, "htank", 30, "infantry", 30)),
                allocate(Map.of("htank", 50, "ltank", 30, "infantry", 20)), true));
        scenarios.add(new Scenario("mixed-aa", "混编：防空护卫 vs 航空联队", "MIXED_COST",
                allocate(Map.of("armored", 40, "htank", 30, "infantry", 30)),
                allocate(Map.of("fighter", 40, "bomber", 60)), true));
        scenarios.add(new Scenario("mixed-rocket-aa", "混编：反坦克火箭车与防空 vs 航空联队", "MIXED_COST",
                allocate(Map.of("rocket", 30, "armored", 30, "infantry", 40)),
                allocate(Map.of("fighter", 40, "bomber", 60)), true));
        Map<String, Integer> mirror = allocate(Map.of("rocket", 30, "armored", 20, "htank", 30, "infantry", 20));
        scenarios.add(new Scenario("mixed-mirror", "混编：同阵容互战", "MIXED_COST", mirror, mirror, true));
        return scenarios;
    }

    private static BattleResult battle(Scenario scenario, boolean developed, int seed, boolean reverse) {
        return new BattleService(seed).startWorldDispatch(reverse ? scenario.b() : scenario.a(), reverse ? scenario.a() : scenario.b(),
                Map.of(), tech(developed), tech(developed), Map.of(), Map.of(), developed ? 50 : 0, developed ? 50 : 0,
                0, 0, "plunder", Map.of(), 0, true);
    }

    private static List<Map<String, Object>> timeline(Scenario scenario, boolean developed, int seed, boolean reverse,
                                                     int initialDistance, BattleResult full) {
        var engine = new BattleService(seed);
        Map<String, Integer> attacker = reverse ? scenario.b() : scenario.a();
        Map<String, Integer> defender = reverse ? scenario.a() : scenario.b();
        // 当前阵型偏移为零。与完整战报逐回合文本核对，阵型规则变化时应失败而非静默输出错误轨迹。
        Map<String, Integer> aPos = new TreeMap<>(), dPos = new TreeMap<>();
        attacker.keySet().forEach(id -> aPos.put(id, 0));
        defender.keySet().forEach(id -> dPos.put(id, initialDistance));
        List<Map<String, Object>> points = new ArrayList<>();
        points.add(point(0, scenario.a(), scenario.b(), scenario, false));
        int firstCombat = 0;
        StringBuilder replayReport = new StringBuilder();
        for (int round = 1; round <= 30; round++) {
            var state = engine.resolveWorldRound(attacker, defender, aPos, dPos, initialDistance,
                    tech(developed), tech(developed), Map.of(), Map.of(), developed ? 50 : 0, 0,
                    developed ? 50 : 0, 0, 0, 0, round, firstCombat, null, null);
            attacker = state.attackerArmy(); defender = state.defenderArmy();
            aPos.clear(); aPos.putAll(state.attackerPositions());
            dPos.clear(); dPos.putAll(state.defenderPositions());
            firstCombat = state.firstCombatRound();
            replayReport.append(state.log());
            points.add(point(round, reverse ? defender : attacker, reverse ? attacker : defender, scenario,
                    Pattern.compile("伤害\\d+").matcher(state.log()).find()));
            if (state.finished()) break;
        }
        require(attacker.equals(full.getSurvivorAttacker()) && defender.equals(full.getSurvivorDefender()), "round/full survivors parity");
        String fullRoundLog = full.getReport().substring(full.getReport().indexOf("-- 第1回合"));
        // finishBattle 会在完整回合日志后附加经验等结算信息。
        require(fullRoundLog.startsWith(replayReport.toString()), "round/full log parity");
        require(points.size() - 1 == lastRound(full.getReport()), "round count parity");
        return points;
    }

    private static Map<String, Object> point(int round, Map<String, Integer> a, Map<String, Integer> b, Scenario scenario, boolean fired) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("round", round); p.put("a_survivors", new TreeMap<>(a)); p.put("b_survivors", new TreeMap<>(b));
        p.put("a_count", count(a)); p.put("b_count", count(b));
        p.put("a_raw_survival_fraction", (double) cost(a, false) / cost(scenario.a(), false));
        p.put("b_raw_survival_fraction", (double) cost(b, false) / cost(scenario.b(), false)); p.put("fired", fired);
        return p;
    }

    private static Map<String, Integer> tech(boolean developed) {
        return developed ? Map.of("attack_tech", 5, "defense_tech", 5, "cmd_hp", 5, "weapon_range", 5,
                "arm_engine", 5, "air_engine", 5, "nav_engine", 5) : Map.of();
    }
    private static int unitCost(String id, boolean weighted) {
        var cost = UnitDef.UNITS.get(id).cost();
        return weighted ? cost.get("steel") + 2 * cost.get("oil") + 4 * cost.get("rare")
                : cost.values().stream().mapToInt(Integer::intValue).sum();
    }
    private static int budgetCount(String id, String budget) {
        return budget.equals("COUNT") ? 100 : BUDGET / unitCost(id, budget.equals("WEIGHTED_COST"));
    }
    private static Map<String, Integer> allocate(Map<String, Integer> shares) {
        Map<String, Integer> army = new TreeMap<>();
        shares.forEach((id, percent) -> army.put(id, BUDGET * percent / 100 / unitCost(id, false)));
        return army;
    }
    private static int count(Map<String, Integer> army) { return army.values().stream().mapToInt(Integer::intValue).sum(); }
    private static long cost(Map<String, Integer> army, boolean weighted) {
        return army.entrySet().stream().mapToLong(e -> (long) e.getValue() * unitCost(e.getKey(), weighted)).sum();
    }
    private static double loss(Map<String, Integer> initial, Map<String, Integer> survivors, boolean weighted) {
        return 1.0 - (double) cost(survivors, weighted) / cost(initial, weighted);
    }
    private static int lastRound(String report) {
        var matcher = ROUND.matcher(report); int round = 0;
        while (matcher.find()) round = Integer.parseInt(matcher.group(1));
        return round;
    }
    private static int distance(String report) {
        var matcher = DISTANCE.matcher(report); require(matcher.find(), "initial distance in report");
        return Integer.parseInt(matcher.group(1));
    }
    private static void validate(BattleResult result, Scenario scenario, boolean reverse) {
        require(result.getInitialAttacker().equals(reverse ? scenario.b() : scenario.a()), "initial attacker");
        require(result.getInitialDefender().equals(reverse ? scenario.a() : scenario.b()), "initial defender");
        for (var pair : List.of(Map.entry(result.getInitialAttacker(), result.getSurvivorAttacker()),
                Map.entry(result.getInitialDefender(), result.getSurvivorDefender()))) {
            pair.getValue().forEach((id, n) -> require(n > 0 && n <= pair.getKey().getOrDefault(id, 0), "survivor bounds"));
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
