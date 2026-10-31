package com.wargame.model.dto;

import com.wargame.util.JsonUtil;
import lombok.Data;
import java.util.*;

/** 驻军协防 v1 的内部持久快照。不得直接通过 API、邮件或分享返回。 */
@Data
public class JointDefenseState {
    public static final String VERSION = "joint-defense-v1-20261024";
    private BattleSkillState skillState = new BattleSkillState();
    private long seed;
    private List<Map<String, Object>> events = new ArrayList<>();
    private Map<String, Formation> formations = new LinkedHashMap<>();

    @Data
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class Formation {
        private String id;
        private String kind;
        private Long playerId;
        private Integer citySlot;
        private Long marchId;
        private Long officerId;
        private String playerName;
        private String officerName;
        private Map<String, Object> commanderSnapshot;
        private int military;
        private int defense;
        private Map<String, Integer> skills = new LinkedHashMap<>();
        private Map<String, Integer> initial = new LinkedHashMap<>();
        private Map<String, Double> damage = new LinkedHashMap<>();
        private Map<String, Double> wallDamage = new LinkedHashMap<>();
        private Map<String, Double> fortDamage = new LinkedHashMap<>();
        private Map<String, Integer> wounded = new LinkedHashMap<>();
        private int firstCombatRound;
        private Set<String> counterUsed = new LinkedHashSet<>();
        /** 编队名称使用客观攻守角色，避免攻方战报将敌城误称为“本城”。 */
        public String title() {
            boolean noOfficer = officerId == null;
            return "support".equals(kind) ? "【援军·" + playerName + "·" + (noOfficer ? "无随队将领" : "将领" + officerName) + "】"
                    : "home".equals(kind) ? "【守方·" + (noOfficer ? "无守将" : "守将" + officerName) + "】"
                    : "【攻方·" + (noOfficer ? "无随队将领" : "将领" + officerName) + "】";
        }

        /** 旧会话仍使用已冻结的有效属性；缺失的等级与基础属性不从实时军官补造。 */
        public Map<String, Object> commanderReport() {
            if (officerId == null) return null;
            if (commanderSnapshot != null) return new LinkedHashMap<>(commanderSnapshot);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", officerName); result.put("military", military); result.put("defense", defense);
            result.put("skills", com.wargame.service.CommanderReportSnapshot.skills(skills));
            return result;
        }
    }

    /** 无此字段的是历史会话；损坏的新快照必须报错，不能回退旧引擎吞掉驻军。 */
    public static JointDefenseState read(String json) {
        if (json == null || json.isBlank()) return null;
        try { return JsonUtil.getMapper().readValue(json, JointDefenseState.class); }
        catch (Exception error) { throw new IllegalStateException("协防快照损坏", error); }
    }

    public Formation source(boolean attacking, String unit) {
        if (attacking) return formations.get("attack");
        return formations.values().stream().filter(f -> !"attack".equals(f.id) && f.initial.containsKey(unit)).findFirst().orElse(formations.get("0"));
    }

    /** 对外身份仅在本场有效，既能区分同名编队，也不暴露行军或原城市数据库 ID。 */
    public String publicId(Formation formation) {
        return "formation-" + new ArrayList<>(formations.keySet()).indexOf(formation.getId());
    }

    /** 双方主将仅投影本场参战快照，驻军的内部来源信息继续保留在私有会话。 */
    public Map<String, Object> commanders() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attacker", formations.get("attack").commanderReport());
        result.put("defender", formations.get("0").commanderReport());
        return result;
    }

    /** 公开贡献只含本场历史事实，不含玩家登录名、来源城市或完整军官属性。 */
    public List<Map<String, Object>> contributions(Map<String, Integer> attackers, Map<String, Integer> defenders) {
        List<Map<String, Object>> rows = new ArrayList<>();
        formations.values().forEach(f -> {
            Map<String, Integer> alive = "attack".equals(f.id) ? attackers : defenders;
            f.initial.forEach((unit, count) -> {
                if (!com.wargame.model.constants.GameData.UNITS.containsKey(com.wargame.model.constants.BattleUnitIds.base(unit))) return;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("formationId", publicId(f));
                row.put("formation", f.title()); row.put("kind", f.kind);
                row.put("unit", com.wargame.model.constants.BattleUnitIds.base(unit));
                row.put("initial", count); row.put("survivors", alive.getOrDefault(unit, 0));
                row.put("losses", Math.max(0, count - alive.getOrDefault(unit, 0)));
                row.put("damage", f.damage.getOrDefault(unit, 0.0)); row.put("wounded", f.wounded.getOrDefault(unit, 0));
                row.put("wallDamage", f.wallDamage.getOrDefault(unit, 0.0));
                row.put("fortDamage", f.fortDamage.getOrDefault(unit, 0.0));
                row.put("unitDamage", Math.max(0, f.damage.getOrDefault(unit, 0.0)
                        - f.wallDamage.getOrDefault(unit, 0.0) - f.fortDamage.getOrDefault(unit, 0.0)));
                rows.add(row);
            });
        });
        return rows;
    }
}
