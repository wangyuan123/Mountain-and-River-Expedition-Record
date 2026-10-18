import com.wargame.model.constants.*;
import com.wargame.model.dto.BattleRoundState;
import com.wargame.service.BattleService;
import com.wargame.service.pvp.PvpJson;
import java.nio.file.*;
import java.util.*;

/** 固定种子多回合对照；使用真实科技键，不改兵种属性、开局距离或终局规则。 */
public class FireDensityBalanceTool {
    private record Pair(String name, Map<String,Integer> a, Map<String,Integer> b) {}
    private static double cost(String id) {
        var resources = UnitDef.UNITS.containsKey(id) ? UnitDef.UNITS.get(id).cost() : FortDef.FORTS.get(id).cost();
        // 仅用于实验的资源总量对照，四资源等权相加，不代表市场价值或匹配实力。
        return resources.values().stream().mapToInt(Integer::intValue).sum();
    }
    private static Map<String,Integer> sized(Map<String,Integer> form, boolean resources) {
        double total = form.entrySet().stream().mapToDouble(e -> e.getValue() * (resources ? cost(e.getKey()) : 1)).sum();
        double budget = resources ? 20_000_000 : 100_000;
        Map<String,Integer> army = new TreeMap<>();
        form.forEach((id,n) -> army.put(id, Math.max(1, (int)Math.round(n * budget / total))));
        return army;
    }
    private static Map<String,Integer> tech(Map<String,Integer> army, int level, int density, boolean gear) {
        Map<String,Integer> tech = new TreeMap<>();
        for (String id : List.of("attack_tech","defense_tech","cmd_hp","weapon_range","arm_engine","air_engine","nav_engine")) tech.put(id, level);
        tech.put("fire_density", density);
        if (gear) army.keySet().stream().filter(UnitDef.UNITS::containsKey).forEach(id -> {
            for (String key : List.of("ground","air","sea","defense","hp","speed")) tech.put(UnitEquipmentEffects.key(id,key), 10);
        });
        return tech;
    }
    private static double loss(Map<String,Integer> initial, Map<String,Integer> current) {
        double all = 0, lost = 0;
        for (var e : initial.entrySet()) {
            all += cost(e.getKey()) * e.getValue();
            lost += cost(e.getKey()) * (e.getValue() - current.getOrDefault(e.getKey(),0));
        }
        return lost / all;
    }
    private static int count(Map<String,Integer> army) { return army.values().stream().mapToInt(Integer::intValue).sum(); }
    public static void main(String[] args) throws Exception {
        Map<String,Integer> allUnits = new TreeMap<>();
        UnitDef.UNITS.keySet().forEach(id -> allUnits.put(id,1));
        var pairs = List.of(
                new Pair("infantry-vs-armor",Map.of("infantry",4,"special",1),Map.of("ltank",2,"htank",1,"rocket",2,"armored",1,"assault",1)),
                new Pair("land-air",Map.of("htank",2,"rocket",4,"armored",2,"fighter",1),Map.of("infantry",4,"ltank",2,"assault",1,"bomber",1,"recon_car",1)),
                new Pair("sea-air",Map.of("sub",2,"destroyer",2,"battleship",1),Map.of("carrier",1,"bomber",2,"fighter",2,"destroyer",1)),
                new Pair("siege",Map.of("htank",2,"rocket",4,"bomber",1),Map.of("infantry",2,"htank",1,"bunker",1,"antitank",1,"howitzer",1,"flak",1)),
                new Pair("all-17-units",allUnits,allUnits));
        var densities = List.of(new int[]{0,0},new int[]{5,5},new int[]{10,10},new int[]{10,0},new int[]{0,10});
        List<Map<String,Object>> rows = new ArrayList<>();
        for (var pair : pairs) for (int level : new int[]{0,5,10}) for (int wall : new int[]{0,10})
            for (boolean gear : new boolean[]{false,true}) for (boolean resources : new boolean[]{false,true}) for (var density : densities) {
                var originalA = sized(pair.a,resources); var originalB = sized(pair.b,resources);
                int samples = 0, wipesA = 0, wipesB = 0, roundLimits = 0;
                double lossA = 0, lossB = 0, rounds = 0, firstRounds = 0;
                Map<String,Integer> exampleA = Map.of(), exampleB = Map.of();
                int initialDistance = 0;
                for (boolean mirror : new boolean[]{false,true}) {
                    // 工事仅在守方参战，攻守互换只用于不含工事的阵容。
                    if (mirror && pair.b.keySet().stream().anyMatch(FortDef.FORTS::containsKey)) continue;
                    for (long seed = 91001; seed <= 91008; seed++) {
                        var initialA = mirror ? originalB : originalA; var initialB = mirror ? originalA : originalB;
                        var at = tech(initialA,level,mirror ? density[1] : density[0],gear);
                        var bt = tech(initialB,level,mirror ? density[0] : density[1],gear);
                        var skills = gear ? Map.of("frenzy",3,"pierce",3,"learn",3,"counter",3) : Map.<String,Integer>of();
                        var engine = new BattleService(seed,false);
                        int distance = engine.calcInitialDistance(initialA,initialB,new BattleService.TechCtx(at,skills,60,60),new BattleService.TechCtx(bt,skills,60,60));
                        initialDistance = distance;
                        Map<String,Integer> a = new TreeMap<>(initialA), b = new TreeMap<>(initialB), ap = new TreeMap<>(), bp = new TreeMap<>();
                        a.keySet().forEach(id -> ap.put(id,0)); b.keySet().forEach(id -> bp.put(id,distance));
                        int first = 0, last = 0;
                        for (int round = 1; round <= 24; round++) {
                            BattleRoundState r = engine.resolveWorldRound(a,b,ap,bp,distance,at,bt,skills,skills,60,60,60,60,wall,wall,round,first,Map.of(),Map.of());
                            a = new TreeMap<>(r.attackerArmy()); b = new TreeMap<>(r.defenderArmy());
                            ap.clear(); ap.putAll(r.attackerPositions()); bp.clear(); bp.putAll(r.defenderPositions());
                            first = r.firstCombatRound(); last = round;
                            if (r.finished()) break;
                        }
                        var finalA = mirror ? b : a; var finalB = mirror ? a : b;
                        lossA += loss(originalA,finalA); lossB += loss(originalB,finalB); rounds += last; firstRounds += first;
                        if (count(finalA)==0) wipesA++; if (count(finalB)==0) wipesB++; if (last==24 && count(a)>0 && count(b)>0) roundLimits++;
                        if (samples==0) { exampleA=finalA; exampleB=finalB; }
                        samples++;
                    }
                }
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("formation",pair.name); row.put("otherTechLevel",level); row.put("wallLevel",wall); row.put("equipmentAndSkills",gear);
                row.put("quantityBasis",resources ? "equal-resource-sum" : "equal-unit-count"); row.put("densityA",density[0]); row.put("densityB",density[1]);
                row.put("samples",samples); row.put("initialDistance",initialDistance); row.put("meanFirstCombatRound",firstRounds/samples); row.put("meanRounds",rounds/samples);
                row.put("meanResourceLossA",lossA/samples); row.put("meanResourceLossB",lossB/samples); row.put("wipesA",wipesA); row.put("wipesB",wipesB); row.put("roundLimitBattles",roundLimits);
                row.put("initialA",originalA); row.put("initialB",originalB); row.put("exampleSurvivorsA",exampleA); row.put("exampleSurvivorsB",exampleB);
                rows.add(row);
            }
        Files.writeString(Path.of(args[0]),PvpJson.write(Map.of("battleRules",BattleRules.VERSION,"seeds",List.of(91001,91002,91003,91004,91005,91006,91007,91008),
                "results",rows,"limitation","synthetic formations; resource sums give all four resources equal weight; gear case also includes skills; not a global win-rate guarantee")));
        System.out.println("configurations="+rows.size()+" simulations="+rows.stream().mapToInt(r -> (Integer)r.get("samples")).sum());
    }
}
