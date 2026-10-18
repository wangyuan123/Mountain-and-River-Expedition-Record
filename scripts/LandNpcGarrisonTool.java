import com.wargame.model.constants.LandNpcGarrison;
import com.wargame.model.constants.JapaneseOfficers;
import com.wargame.service.BattleService;
import com.wargame.util.JsonUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 导出实际配置与跨阶段固定种子战斗样本；在项目根目录运行，结果只供配兵审阅。 */
public class LandNpcGarrisonTool {
    private static final List<String> UNITS = List.of("infantry", "armored", "ltank", "fighter", "bomber",
            "htank", "rocket", "special");
    private static final long[] SEEDS = {101501, 101502, 101503, 101504};

    /** 参数依次为完整守军 CSV 和跨阶段战斗 CSV 的输出路径。 */
    public static void main(String[] args) throws Exception {
        StringBuilder rows = new StringBuilder("level,formation,budget,actual_strength,total," + String.join(",", UNITS) + "\n");
        for (int level = 1; level <= 30; level++) {
            var choices = LandNpcGarrison.formations(level);
            for (int variant = 0; variant < choices.size(); variant++) {
                var army = choices.get(variant);
                rows.append(String.format(Locale.ROOT, "%d,%d,%.3f,%.3f,%d", level, variant,
                        LandNpcGarrison.budget(level), LandNpcGarrison.strength(army),
                        army.values().stream().mapToInt(Integer::intValue).sum()));
                for (String unit : UNITS) rows.append(',').append(army.getOrDefault(unit, 0));
                rows.append('\n');
            }
        }
        Files.writeString(Path.of(args[0]), rows);
        // 每对相邻等级使用相同参考军、兵力和种子；包含现有 NPC 军官，记录战损而非宣称真人胜率。
        List<Map<String, Integer>> references = List.of(Map.of("infantry", 6, "armored", 1),
                Map.of("ltank", 2, "armored", 1), Map.of("fighter", 2, "bomber", 1),
                Map.of("infantry", 3, "ltank", 1, "armored", 1, "fighter", 1),
                Map.of("rocket", 1, "armored", 2, "fighter", 1));
        StringBuilder battles = new StringBuilder("boundary,level,formation,reference,seed,attacker_strength,attacker_loss,defender_loss,defender_advantage\n");
        for (int lower : new int[]{5, 10, 15, 24}) {
            double attackBudget = Math.sqrt(LandNpcGarrison.budget(lower) * LandNpcGarrison.budget(lower + 1));
            for (int ref = 0; ref < references.size(); ref++) {
                var attack = scaled(references.get(ref), attackBudget);
                for (int level : new int[]{lower, lower + 1}) {
                    var officer = JapaneseOfficers.buildOfficer(null, level);
                    Map<String, Integer> skills = new LinkedHashMap<>();
                    for (var skill : JsonUtil.parseList(officer.getSkills()))
                        skills.put((String) skill.get("id"), ((Number) skill.get("lv")).intValue());
                    var choices = LandNpcGarrison.formations(level);
                    for (int variant = 0; variant < choices.size(); variant++) {
                        var defense = choices.get(variant);
                        for (long seed : SEEDS) {
                            var result = new BattleService(seed).startWorldDispatch(attack, defense, Map.of(), Map.of(),
                                    Map.of(), Map.of(), skills, 60, 60, officer.getMilitary(), officer.getDefense(),
                                    "conquer", Map.of(), 0, false);
                            double ownLoss = loss(attack, result.getSurvivorAttacker());
                            double npcLoss = loss(defense, result.getSurvivorDefender());
                            battles.append(String.format(Locale.ROOT, "%d-%d,%d,%d,%d,%d,%.3f,%.6f,%.6f,%.6f%n",
                                    lower, lower + 1, level, variant, ref, seed, LandNpcGarrison.strength(attack),
                                    ownLoss, npcLoss, ownLoss - npcLoss));
                        }
                    }
                }
            }
        }
        Files.writeString(Path.of(args[1]), battles);
        System.out.println("garrisons=" + (rows.toString().lines().count() - 1)
                + " battles=" + (battles.toString().lines().count() - 1));
    }

    private static Map<String, Integer> scaled(Map<String, Integer> profile, double budget) {
        double scale = budget / LandNpcGarrison.strength(profile);
        Map<String, Integer> army = new LinkedHashMap<>();
        profile.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                army.put(e.getKey(), Math.max(1, (int) Math.round(e.getValue() * scale))));
        return army;
    }

    private static double loss(Map<String, Integer> initial, Map<String, Integer> remaining) {
        return 1 - LandNpcGarrison.strength(remaining) / LandNpcGarrison.strength(initial);
    }
}
