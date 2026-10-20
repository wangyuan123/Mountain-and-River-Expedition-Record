import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.constants.BattleRules;
import com.wargame.model.constants.BattleScene;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.dto.BattleRoundState;
import com.wargame.model.dto.CommandBonus;
import com.wargame.service.BattleService;
import java.nio.file.Path;
import java.util.*;

/** 只在隔离类路径内改变海军属性，沿用当前35回合世界战斗内核。 */
public class NavyBalanceSimulation {
    static final int SEEDS = 32;
    static final Map<String, Integer> TECH = Map.of("attack_tech", 10, "defense_tech", 10,
            "cmd_hp", 10, "fire_density", 10, "weapon_range", 10,
            "arm_engine", 10, "air_engine", 10, "nav_engine", 10);
    record Scenario(String id, Map<String,Integer> attacker, Map<String,Integer> defender,
                    BattleScene scene, int am, int ad, int dm, int dd) {}
    static int total(Map<String,Integer> army) { return army.values().stream().mapToInt(Integer::intValue).sum(); }
    static Map<String,Integer> scale(Map<String,Integer> army, double factor) {
        Map<String,Integer> result = new TreeMap<>();
        army.forEach((key,count) -> result.put(key, (int)Math.round(count * factor)));
        return result;
    }
    static Map<String,Object> run(Scenario s) {
        var ac = new BattleService.TechCtx(TECH, Map.of(), s.am, s.ad);
        var dc = new BattleService.TechCtx(TECH, Map.of(), s.dm, s.dd);
        int distance = new BattleService(0).calcInitialDistance(s.attacker, s.defender, ac, dc, null, s.scene);
        int wins = 0, losses = 0, timeouts = 0, minEnd = 35, maxEnd = 0, minFirst = 35, maxFirst = 0;
        double sumA = 0, sumD = 0, sumEnd = 0;
        Map<String,Double> sumUnitsA = new TreeMap<>(), sumUnitsD = new TreeMap<>();
        List<Map<String,Object>> sample = new ArrayList<>();
        for (int seed = 0; seed < SEEDS; seed++) {
            var engine = new BattleService(seed);
            Map<String,Integer> a = new TreeMap<>(s.attacker), d = new TreeMap<>(s.defender);
            Map<String,Integer> ap = new TreeMap<>(), dp = new TreeMap<>();
            a.keySet().forEach(id -> ap.put(id, 0)); d.keySet().forEach(id -> dp.put(id, distance));
            int first = 0;
            for (int round = 1; round <= 35; round++) {
                BattleRoundState state = engine.resolveWorldRound(a, d, ap, dp, distance,
                        TECH, TECH, Map.of(), Map.of(), s.am, s.ad, s.dm, s.dd, round, first,
                        Map.of(), Map.of(), CommandBonus.NONE, CommandBonus.NONE, "conquer");
                a = new TreeMap<>(state.attackerArmy()); d = new TreeMap<>(state.defenderArmy());
                ap.clear(); ap.putAll(state.attackerPositions()); dp.clear(); dp.putAll(state.defenderPositions());
                first = state.firstCombatRound();
                if (seed == 0) sample.add(Map.of("round", round, "attacker", a, "defender", d));
                if (state.finished()) {
                    if (state.attackerWin()) wins++;
                    else if ("ROUND_LIMIT".equals(state.terminalReason())) timeouts++;
                    else losses++;
                    minEnd = Math.min(minEnd, round); maxEnd = Math.max(maxEnd, round);
                    minFirst = Math.min(minFirst, first); maxFirst = Math.max(maxFirst, first);
                    sumA += total(a); sumD += total(d); sumEnd += round;
                    a.forEach((id,count) -> sumUnitsA.merge(id, count / (double)SEEDS, Double::sum));
                    d.forEach((id,count) -> sumUnitsD.merge(id, count / (double)SEEDS, Double::sum));
                    break;
                }
                if (round == 35) throw new IllegalStateException("No terminal state");
            }
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("id",s.id); result.put("scene",s.scene); result.put("initialAttacker",s.attacker);
        result.put("initialDefender",s.defender); result.put("commanders",List.of(s.am,s.ad,s.dm,s.dd));
        result.put("distance",distance); result.put("firstCombatRange",List.of(minFirst,maxFirst));
        result.put("endRoundRange",List.of(minEnd,maxEnd)); result.put("meanEndRound",sumEnd / SEEDS);
        result.put("wins",wins); result.put("losses",losses); result.put("timeouts",timeouts);
        result.put("attackerRemaining",sumA / SEEDS); result.put("defenderRemaining",sumD / SEEDS);
        result.put("attackerRemainingPct",100 * sumA / SEEDS / total(s.attacker));
        result.put("defenderRemainingPct",100 * sumD / SEEDS / total(s.defender));
        result.put("attackerRemainingUnits",sumUnitsA); result.put("defenderRemainingUnits",sumUnitsD);
        result.put("sampleSeed0",sample);
        System.out.printf(Locale.ROOT,"%s first=%d end=%d-%d W/L/T=%d/%d/%d A=%.1f%% D=%.1f%%%n",
                s.id,minFirst,minEnd,maxEnd,wins,losses,timeouts,
                100 * sumA / SEEDS / total(s.attacker),100 * sumD / SEEDS / total(s.defender));
        return result;
    }
    public static void main(String[] args) throws Exception {
        List<Scenario> scenarios = new ArrayList<>();
        Map<String,Integer> mixed = Map.of("destroyer",27523,"sub",42857,"battleship",10000,"carrier",7895);
        for (BattleScene scene : List.of(BattleScene.NPC,BattleScene.PLAYER)) {
            for (String unit : List.of("battleship","destroyer","sub","carrier"))
                for (double ratio : List.of(1.0,1.5,2.0))
                    scenarios.add(new Scenario(unit+"-"+ratio+"-"+scene,
                            Map.of(unit,(int)(10000*ratio)),Map.of(unit,10000),scene,0,0,0,0));
            for (double ratio : List.of(1.0,1.5,2.0))
                scenarios.add(new Scenario("mixed-"+ratio+"-"+scene,scale(mixed,ratio),mixed,scene,0,0,0,0));
        }
        scenarios.add(new Scenario("battleship-equal-officers100",Map.of("battleship",10000),
                Map.of("battleship",10000),BattleScene.NPC,100,100,100,100));
        scenarios.add(new Scenario("sub-vs-battleship-equal-cost",Map.of("sub",42857),
                Map.of("battleship",10000),BattleScene.NPC,0,0,0,0));
        scenarios.add(new Scenario("destroyer-vs-sub-equal-cost",Map.of("destroyer",27523),
                Map.of("sub",42857),BattleScene.NPC,0,0,0,0));
        Map<String,Integer> actualA = Map.of("battleship",10000,"carrier",5000);
        Map<String,Integer> d3725 = Map.of("sub",36400,"bomber",8400,"carrier",660,
                "fighter",16800,"destroyer",14280,"battleship",5320);
        Map<String,Integer> d3722 = Map.of("sub",34000,"bomber",6800,"carrier",540,
                "fighter",13600,"destroyer",13056,"battleship",4760);
        for (var entry : Map.of("3725",d3725,"3722",d3722).entrySet()) {
            scenarios.add(new Scenario("composition-"+entry.getKey()+"-no-officer",actualA,entry.getValue(),BattleScene.NPC,0,0,0,0));
            scenarios.add(new Scenario("composition-"+entry.getKey()+"-assumed-officer219",actualA,entry.getValue(),BattleScene.NPC,219,75,0,0));
        }
        List<Map<String,Object>> results = new ArrayList<>();
        for (Scenario scenario : scenarios) results.add(run(scenario));
        Map<String,Object> output = new LinkedHashMap<>();
        output.put("variant",args[0]); output.put("ruleVersion",BattleRules.VERSION); output.put("seeds",SEEDS);
        output.put("techBothSides",TECH); output.put("skills",Map.of());
        output.put("assumptions","自动前进、无装备、无司令官加成、无城墙；真实战报仅复用兵力，不是精确重放");
        Map<String,Object> attributes = new TreeMap<>();
        UnitDef.UNITS.forEach((id,u)-> {if ("sea".equals(u.branch())) attributes.put(id,Map.of("defense",u.def(),"seaAttack",u.atkSea(),"hp",u.hp()));});
        output.put("navyAttributes",attributes); output.put("scenarios",results);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),output);
    }
}
