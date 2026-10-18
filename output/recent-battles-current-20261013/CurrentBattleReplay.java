import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.constants.BattleRules;
import com.wargame.model.constants.GameData;
import com.wargame.model.dto.BattleRoundState;
import com.wargame.model.dto.CommandBonus;
import com.wargame.service.BattleService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** 读取原始战报兵力，直接调用当前世界战斗内核；不重新结算线上战报。 */
public class CurrentBattleReplay {
    static final ObjectMapper JSON = new ObjectMapper();
    static final int SEEDS = 32;
    static Path root;

    static Map<String, Integer> tech(int density) {
        return new LinkedHashMap<>(Map.of("attack_tech", 10, "defense_tech", 10,
                "cmd_hp", 10, "weapon_range", 10, "arm_engine", 10,
                "air_engine", 10, "nav_engine", 10, "fire_density", density));
    }
    static int total(Map<String, Integer> army) {
        return army.values().stream().mapToInt(Integer::intValue).sum();
    }
    static Map<String, Integer> army(Object source) {
        return JSON.convertValue(source, new TypeReference<LinkedHashMap<String, Integer>>() {});
    }
    static Map<String, BattleService.UnitOrder> orders(int battle, int round, String mode,
            Map<Integer, Map<String, BattleService.UnitOrder>> historical) {
        if (mode.equals("auto")) return Map.of();
        // 原战报只到24回合；延长部分保持最后机动/集火指令，不擅自新增指挥。
        return historical.get(Math.min(24, round));
    }
    static Map<Integer, Map<String, BattleService.UnitOrder>> parseOrders(String report, int battle, boolean mine) {
        Map<Integer, Map<String, BattleService.UnitOrder>> result = new TreeMap<>();
        String side = mine ? "我方" : "敌方";
        int round = 0;
        for (String line : report.split("\\R")) {
            var header = Pattern.compile("^-- 第(\\d+)回合 --").matcher(line);
            if (header.find()) { round = Integer.parseInt(header.group(1)); result.put(round, new TreeMap<>()); }
            if (!line.startsWith(side) || round == 0) continue;
            var action = Pattern.compile("\\[(前进|后退|待命)\\]").matcher(line);
            if (!action.find()) continue;
            for (var unit : GameData.UNITS.values()) {
                if (!line.startsWith(side + unit.name() + "(")) continue;
                BattleService.CommandAction command = switch (action.group(1)) {
                    case "前进" -> BattleService.CommandAction.ADVANCE;
                    case "后退" -> BattleService.CommandAction.RETREAT;
                    default -> BattleService.CommandAction.HOLD;
                };
                // 集火未命中时日志没有标记；沿用此前复现所核对的第二场集火时间段。
                String focus = mine && battle == 3684 && unit.key().equals("rocket")
                        ? round >= 23 ? "htank" : round >= 15 ? "armored" : null : null;
                result.get(round).put(unit.key(), new BattleService.UnitOrder(command, focus));
            }
        }
        if (result.size() != 24) throw new IllegalStateException("Expected 24 historical rounds");
        return result;
    }
    static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    static Map<String, Object> run(int battle, Map<String, Object> raw, int adensity, int ddensity, String mode) throws Exception {
        Map<String, Integer> a0 = army(raw.get("initialAttacker")), d0 = army(raw.get("initialDefender"));
        int am = battle == 3682 ? 219 : 100, ad = battle == 3682 ? 75 : 100;
        int dm = battle == 3682 ? 160 : 0, dd = battle == 3682 ? 205 : 0;
        Map<String, Integer> as = battle == 3682 ? Map.of("frenzy", 5, "borrow_armor", 5, "suppress", 2) : Map.of("ration", 5);
        Map<String, Integer> ds = battle == 3682 ? Map.of("bulwark", 5, "counter", 5, "medic", 5) : Map.of();
        String action = battle == 3682 ? "conquer" : "plunder";
        int cap = BattleService.maxRoundForAction(action);
        Map<String, Integer> at = tech(adensity), dt = tech(ddensity);
        var ac = new BattleService.TechCtx(at, as, am, ad);
        var dc = new BattleService.TechCtx(dt, ds, dm, dd);
        int distance = new BattleService(0).calcInitialDistance(a0, d0, ac, dc);
        var ao = parseOrders((String) raw.get("report"), battle, true);
        var bo = parseOrders((String) raw.get("report"), battle, false);
        long[] sumA = new long[cap + 1], sumD = new long[cap + 1];
        int[] minA = new int[cap + 1], minD = new int[cap + 1], maxA = new int[cap + 1], maxD = new int[cap + 1];
        Arrays.fill(minA, Integer.MAX_VALUE); Arrays.fill(minD, Integer.MAX_VALUE);
        List<Map<String, Long>> unitsA = new ArrayList<>(), unitsD = new ArrayList<>();
        List<Map<String, Object>> sample = new ArrayList<>();
        for (int r = 0; r <= cap; r++) { unitsA.add(new TreeMap<>()); unitsD.add(new TreeMap<>()); }
        int wins = 0, defeats = 0, timeouts = 0, minRound = cap, maxRound = 0;
        int minCombat = Integer.MAX_VALUE, maxCombat = 0;
        StringBuilder log = new StringBuilder("开局距离=" + distance + " 上限=" + cap + "\n");
        for (long seed = 0; seed < SEEDS; seed++) {
            BattleService engine = new BattleService(seed);
            Map<String, Integer> a = new LinkedHashMap<>(a0), d = new LinkedHashMap<>(d0);
            Map<String, Integer> ap = new TreeMap<>(), dp = new TreeMap<>();
            for (String id : a0.keySet()) ap.put(id, 0);
            for (String id : d0.keySet()) dp.put(id, distance);
            int first = 0, endRound = cap;
            boolean finished = false;
            for (int r = 0; r <= cap; r++) {
                if (r > 0 && !finished) {
                    BattleRoundState state = engine.resolveWorldRound(a, d, ap, dp, distance, at, dt, as, ds,
                            am, ad, dm, dd, 10, 10, r, first, orders(battle, r, mode, ao), orders(battle, r, mode, bo),
                            CommandBonus.NONE, CommandBonus.NONE, action);
                    a = new LinkedHashMap<>(state.attackerArmy()); d = new LinkedHashMap<>(state.defenderArmy());
                    ap = new TreeMap<>(state.attackerPositions()); dp = new TreeMap<>(state.defenderPositions());
                    first = state.firstCombatRound(); finished = state.finished();
                    if (seed == 0) log.append(state.log());
                    if (finished) { endRound = r; if (state.attackerWin()) wins++; else if (total(a) == 0) defeats++; else timeouts++; }
                }
                int na = total(a), nd = total(d);
                sumA[r] += na; sumD[r] += nd;
                minA[r] = Math.min(minA[r], na); minD[r] = Math.min(minD[r], nd);
                maxA[r] = Math.max(maxA[r], na); maxD[r] = Math.max(maxD[r], nd);
                for (String id : a0.keySet()) unitsA.get(r).merge(id, (long) a.getOrDefault(id, 0), Long::sum);
                for (String id : d0.keySet()) unitsD.get(r).merge(id, (long) d.getOrDefault(id, 0), Long::sum);
                if (seed == 0) sample.add(Map.of("round", r, "attacker", new TreeMap<>(a), "defender", new TreeMap<>(d),
                        "attackerPositions", new TreeMap<>(ap), "defenderPositions", new TreeMap<>(dp), "finished", finished));
            }
            if (!finished) throw new IllegalStateException("Battle did not finish at cap");
            minRound = Math.min(minRound, endRound); maxRound = Math.max(maxRound, endRound);
            minCombat = Math.min(minCombat, first); maxCombat = Math.max(maxCombat, first);
        }
        List<Map<String, Object>> rounds = new ArrayList<>();
        for (int r = 0; r <= cap; r++) {
            Map<String, Double> ma = new TreeMap<>(), md = new TreeMap<>();
            unitsA.get(r).forEach((id, count) -> ma.put(id, count / (double) SEEDS));
            unitsD.get(r).forEach((id, count) -> md.put(id, count / (double) SEEDS));
            rounds.add(Map.of("round", r, "attackerRemaining", sumA[r] / (double) SEEDS,
                    "defenderRemaining", sumD[r] / (double) SEEDS, "attackerRange", List.of(minA[r], maxA[r]),
                    "defenderRange", List.of(minD[r], maxD[r]), "attackerUnits", ma, "defenderUnits", md));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", battle + "-" + mode + "-" + adensity + "-" + ddensity);
        result.put("reportId", battle); result.put("action", action); result.put("mode", mode);
        result.put("attackerDensity", adensity); result.put("defenderDensity", ddensity);
        result.put("distance", distance); result.put("maxRound", cap);
        result.put("firstCombatRange", List.of(minCombat, maxCombat)); result.put("endRoundRange", List.of(minRound, maxRound));
        result.put("wins", wins); result.put("defeats", defeats); result.put("timeouts", timeouts);
        result.put("attackerInitial", a0); result.put("defenderInitial", d0);
        result.put("attackerTech", at); result.put("defenderTech", dt);
        result.put("commanderStats", Map.of("attackerMilitary", am, "attackerDefense", ad, "defenderMilitary", dm, "defenderDefense", dd));
        result.put("attackerSkills", as); result.put("defenderSkills", ds);
        result.put("wallLevels", List.of(10, 10)); result.put("rounds", rounds); result.put("sampleSeed0", sample);
        String filename = (String) result.get("id");
        Files.writeString(root.resolve("output/recent-battles-current-20261013/" + filename + ".log"), log);
        var last = rounds.get(cap);
        System.out.printf(Locale.ROOT, "%s distance=%d first=%d-%d A=%.1f D=%.1f wins=%d defeats=%d timeouts=%d%n",
                filename, distance, minCombat, maxCombat, last.get("attackerRemaining"), last.get("defenderRemaining"), wins, defeats, timeouts);
        return result;
    }
    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]);
        List<Map<String, Object>> results = new ArrayList<>();
        for (int battle : List.of(3682, 3684)) {
            var raw = JSON.readValue(root.resolve("output/battle-analysis-20261012/battle-" + battle + ".json").toFile(),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            for (String mode : List.of("auto", "historical"))
                for (int[] density : List.of(new int[]{0, 0}, new int[]{5, 5}, new int[]{10, 10}, new int[]{10, 0}))
                    results.add(run(battle, raw, density[0], density[1], mode));
        }
        Map<String, String> hashes = new TreeMap<>();
        for (String file : List.of("backend/src/main/java/com/wargame/service/BattleService.java",
                "backend/src/main/java/com/wargame/model/constants/UnitDef.java",
                "backend/src/main/java/com/wargame/model/constants/BattleRules.java",
                "output/battle-analysis-20261012/battle-3682.json", "output/battle-analysis-20261012/battle-3684.json"))
            hashes.put(file, hash(root.resolve(file)));
        Map<String, String> names = new TreeMap<>();
        GameData.UNITS.forEach((id, u) -> names.put(id, u.name().split("-")[0]));
        Map<String, Object> attributes = new TreeMap<>();
        var attributeEngine = new BattleService(0);
        var attributeContext = new BattleService.TechCtx(tech(0), Map.of(), 0, 0);
        GameData.UNITS.forEach((id, u) -> {
            var stats = attributeEngine.practiceStats(id, attributeContext);
            attributes.put(id, Map.of("baseStep", u.spd() * 50,
                    "stepWithEngine10", (int) (stats.get("speed") * 50),
                    "baseRange", u.range(), "rangeWithTech10", stats.get("range"),
                    "autoAdvance", u.autoAdvance() == null || u.autoAdvance()));
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ruleVersion", BattleRules.VERSION); data.put("seeds", SEEDS); data.put("sourceHashes", hashes);
        data.put("unitNames", names); data.put("currentAttributes", attributes); data.put("scenarios", results);
        JSON.writerWithDefaultPrettyPrinter().writeValue(root.resolve("output/recent-battles-current-20261013/results.json").toFile(), data);
    }
}
