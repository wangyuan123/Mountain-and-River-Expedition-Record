package com.wargame.service;

import com.wargame.util.GameClock;
import com.wargame.model.constants.*;
import com.wargame.model.dto.CommandBonus;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** 军情权限边界：城市来袭按雷达解锁，野外截击固定为粗略白名单；查询与推送共用投影。 */
@Service
@RequiredArgsConstructor
public class IncomingIntelligenceService {
    private final TechnologyRepository technologies;
    private final BattleSessionRepository sessions;
    private final MarchTargetService targets;
    private final EquipmentService equipment;

    public int radar(Long defender) {
        return technologies.findByPlayerIdAndType(defender, "recon_radar").stream()
                .mapToInt(t -> Objects.requireNonNullElse(t.getLevel(), 0)).max().stream()
                .map(n -> Math.max(0, Math.min(10, n))).findFirst().orElse(0);
    }

    /**
     * 在途编队遭遇截击时只提供野外急报；本城雷达等级不能提高此处的情报权限。
     * @param interceptor 敌方截击编队
     * @param intercepted 我方被截击行军
     * @param sourcePlayer 敌方玩家身份，只允许读取公开名称
     * @return 不含精确兵力、科技、装备或将领属性的固定白名单
     */
    public Map<String, Object> fieldInterception(March interceptor, March intercepted, Player sourcePlayer) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", "interception-" + interceptor.getId());
        raw.put("marchId", interceptor.getId());
        raw.put("fromName", sourcePlayer == null ? "未知敌军" : sourcePlayer.getUsername());
        raw.put("action", "intercept_scout");
        raw.put("fromX", interceptor.getFromX());
        raw.put("fromY", interceptor.getFromY());
        raw.put("targetX", interceptor.getTargetX());
        raw.put("targetY", interceptor.getTargetY());
        raw.put("targetName", "拦截交火位置");
        raw.put("startAt", interceptor.getStartAt());
        raw.put("arriveAt", interceptor.getArriveAt());
        raw.put("army", JsonUtil.parseIntMap(interceptor.getArmy()));
        // 复用固定兵力分档与兵种白名单，但不把雷达授权语义带入野外情报。
        Map<String, Object> out = project(raw, 5);
        out.remove("radarLevel");
        out.put("intelligenceType", "field_interception");
        out.put("interceptedMarchId", intercepted.getId());
        out.put("interceptedCitySlot", Objects.requireNonNullElse(intercepted.getCitySlot(), 0));
        out.put("sourceCommanderName", sourcePlayer == null ? "未知"
                : sourcePlayer.getDisplayName() == null || sourcePlayer.getDisplayName().isBlank()
                ? sourcePlayer.getUsername() : sourcePlayer.getDisplayName());
        out.put("intelligenceSource", "野外目视与前线急报；无雷达支援，仅提供粗略情报");
        out.put("intelligenceAt", GameClock.now());
        putFieldOfficer(out, "commander", targets.getOfficerById(interceptor.getPlayerId(), interceptor.getCommanderId()));
        putFieldOfficer(out, "commandChief", targets.getCommanderInChief(interceptor.getPlayerId(), Objects.requireNonNullElse(interceptor.getCitySlot(), 0)));
        return out;
    }

    /** 仅公开将领的身份、星级和等级，不复用携带属性与加成的战斗对象。 */
    private static void putFieldOfficer(Map<String, Object> out, String key, Officer officer) {
        out.put(key, officer == null ? "未任命" : officer.getName());
        if (officer != null) {
            out.put(key + "Star", officer.getStar());
            out.put(key + "Level", officer.getLevel());
        }
    }

    /** 先按授权补充本队情报，再白名单投影；旧来袭缺少高级快照时保持未知。 */
    public Map<String, Object> view(int level, Map<String, Object> raw, March march) {
        Map<String, Object> data = new LinkedHashMap<>(raw);
        if (march != null && level >= 8) {
            Officer officer = targets.getOfficerById(march.getPlayerId(), march.getCommanderId());
            if (officer != null) {
                data.put("commander", officer.getName());
                data.put("commanderStar", officer.getStar());
                data.put("commanderLevel", officer.getLevel());
            }
            if (level >= 9) {
                BattleSession session = march.getBattleId() == null ? null : sessions.findById(march.getBattleId()).orElse(null);
                Map<String, Integer> tech = session == null ? targets.getTechMap(march.getPlayerId()) : JsonUtil.parseIntMap(session.getAttackerTech());
                Map<String, Integer> army = JsonUtil.parseIntMap(march.getArmy());
                Set<String> applicable = new HashSet<>(Set.of("attack_tech", "defense_tech", "cmd_hp", "fire_density", "weapon_range"));
                army.forEach((id, count) -> {
                    UnitDef unit = UnitDef.UNITS.get(id);
                    if (unit != null && count > 0) applicable.add(switch (unit.branch()) {
                        case "air" -> "air_engine"; case "sea" -> "nav_engine"; default -> "arm_engine";
                    });
                });
                if ("scout".equals(march.getAction())) {
                    applicable.clear(); applicable.add("recon_level");
                }
                Map<String, Integer> combat = new TreeMap<>();
                for (String key : applicable) combat.put(key, TechnologyRules.level(tech, key, 10));
                data.put("combatTech", combat);
                Map<String, Integer> gear = session == null ? JsonUtil.parseIntMap(march.getUnitEquipmentSnapshot()) : tech;
                data.put("unitEquipment", UnitEquipmentEffects.only(UnitEquipmentEffects.forArmy(gear, army)));
                if (level >= 10) {
                    if (session == null) {
                        Officer chief = targets.getCommanderInChief(march.getPlayerId(), Objects.requireNonNullElse(march.getCitySlot(), 0));
                        var c = equipment.attributes(chief);
                        data.put("commandBonus", (chief == null ? CommandBonus.NONE : new CommandBonus(chief.getId(), chief.getName(), c.military(), c.defense(), c.logistics(), c.knowledge())).toMap());
                    } else {
                        data.put("commandBonus", CommandBonus.fromJson(session.getAttackerCommandBonus()).toMap());
                    }
                }
                data.put("intelligenceAt", GameClock.now());
                data.put("intelligenceSource", session == null ? "装备为出征快照；科技和将领为查询时数据" : "战斗属性为开战快照；将领姓名等级为查询时数据");
            }
        }
        return project(data, level);
    }

    /** 十级权限逐字段累进；带兵将领的有效属性和技能始终不下发，固定兵力区间避免推断精确数量。 */
    public static Map<String, Object> project(Map<String, Object> raw, int radar) {
        int level = Math.max(0, Math.min(10, radar));
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("id", "marchId", "targetCityId", "targetName", "targetX", "targetY", "arriveAt", "arrived", "inBattle", "waitingForBattle", "event", "startAt", "countermeasuresUsed")) {
            if (raw.containsKey(key)) out.put(key, raw.get(key));
        }
        out.put("radarLevel", level);
        if (level >= 1) copy(raw, out, "fromName", "attackerName", "sourcePlayer", "action");
        if (level >= 2) copy(raw, out, "fromX", "fromY", "fromCityName");
        Map<String, Integer> army = new TreeMap<>();
        if (raw.get("army") instanceof Map<?, ?> values) values.forEach((k, v) -> {
            if (v instanceof Number n && n.intValue() > 0 && UnitDef.UNITS.containsKey(String.valueOf(k))) army.put(String.valueOf(k), n.intValue());
        });
        long total = army.values().stream().mapToLong(Integer::longValue).sum();
        if (level >= 3) {
            out.put("branches", army.keySet().stream().map(id -> UnitDef.UNITS.get(id).branch()).distinct().sorted().toList());
        }
        if (level >= 4) {
            long lower = 1;
            String band = "未知";
            for (long upper : new long[]{100, 500, 1000, 5000, 10000, 50000, 100000, 500000, Long.MAX_VALUE}) {
                if (total > 0 && total <= upper) { band = upper == Long.MAX_VALUE ? lower + "以上" : lower + "—" + upper; break; }
                lower = upper + 1;
            }
            out.put("strengthBand", band);
        }
        if (level >= 5) out.put("unitTypes", new ArrayList<>(army.keySet()));
        if (level >= 6) out.put("totalTroops", total);
        if (level >= 7 && raw.containsKey("army")) out.put("army", army);
        if (level >= 8) copy(raw, out, "commander", "commanderStar", "commanderLevel");
        if (level >= 9) copy(raw, out, "combatTech", "unitEquipment", "intelligenceAt", "intelligenceSource");
        if (level >= 10) copy(raw, out, "commandBonus");
        return out;
    }

    private static void copy(Map<String, Object> from, Map<String, Object> to, String... keys) {
        for (String key : keys) if (from.containsKey(key)) to.put(key, from.get(key));
    }
}
