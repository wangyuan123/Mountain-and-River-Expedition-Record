package com.wargame.service;

import com.wargame.model.constants.*;
import com.wargame.model.dto.CommandBonus;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** 来袭情报唯一权限边界；世界状态、军情和推送共享白名单，未解锁字段不会到达客户端。 */
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
                .map(n -> Math.max(0, Math.min(5, n))).findFirst().orElse(0);
    }

    /** 先按授权补充本队情报，再白名单投影；旧来袭缺少高级快照时保持未知。 */
    public Map<String, Object> view(int level, Map<String, Object> raw, March march) {
        Map<String, Object> data = new LinkedHashMap<>(raw);
        if (march != null && level >= 4) {
            Officer officer = targets.getOfficerById(march.getPlayerId(), march.getCommanderId());
            if (officer != null) {
                data.put("commander", officer.getName());
                data.put("commanderStar", officer.getStar());
                data.put("commanderLevel", officer.getLevel());
            }
            if (level >= 5) {
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
                for (String key : applicable) combat.put(key, TechnologyRules.level(tech, key, "recon_level".equals(key) ? 5 : 10));
                data.put("combatTech", combat);
                Map<String, Integer> gear = session == null ? JsonUtil.parseIntMap(march.getUnitEquipmentSnapshot()) : tech;
                data.put("unitEquipment", UnitEquipmentEffects.only(UnitEquipmentEffects.forArmy(gear, army)));
                data.put("skills", session == null ? targets.getCommanderSkills(officer) : JsonUtil.parseIntMap(session.getAttackerSkills()));
                Map<String, Object> attrs = new LinkedHashMap<>();
                if (session == null) {
                    var a = equipment.attributes(officer);
                    attrs.put("military", a.military()); attrs.put("defense", a.defense());
                    Officer chief = targets.getCommanderInChief(march.getPlayerId(), Objects.requireNonNullElse(march.getCitySlot(), 0));
                    var c = equipment.attributes(chief);
                    data.put("commandBonus", (chief == null ? CommandBonus.NONE : new CommandBonus(chief.getId(), chief.getName(), c.military(), c.defense(), c.logistics(), c.knowledge())).toMap());
                } else {
                    attrs.put("military", session.getAttackerCommanderMil()); attrs.put("defense", session.getAttackerCommanderDef());
                    data.put("commandBonus", CommandBonus.fromJson(session.getAttackerCommandBonus()).toMap());
                }
                data.put("commanderAttributes", attrs);
                data.put("intelligenceAt", System.currentTimeMillis());
                data.put("intelligenceSource", session == null ? "装备为出征快照；科技和将领为查询时数据" : "战斗属性为开战快照；将领姓名等级为查询时数据");
            }
        }
        return project(data, level);
    }

    /** 固定兵力档位防止重复刷新推断区间内数量；Lv.3只给兵种名，不携带各兵种数量。 */
    public static Map<String, Object> project(Map<String, Object> raw, int radar) {
        int level = Math.max(0, Math.min(5, radar));
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("id", "marchId", "targetCityId", "targetName", "targetX", "targetY", "arriveAt", "arrived", "inBattle", "waitingForBattle", "event", "startAt")) {
            if (raw.containsKey(key)) out.put(key, raw.get(key));
        }
        out.put("radarLevel", level);
        if (level >= 1) copy(raw, out, "fromName", "attackerName", "sourcePlayer", "fromX", "fromY", "action");
        Map<String, Integer> army = new TreeMap<>();
        if (raw.get("army") instanceof Map<?, ?> values) values.forEach((k, v) -> {
            if (v instanceof Number n && n.intValue() > 0 && UnitDef.UNITS.containsKey(String.valueOf(k))) army.put(String.valueOf(k), n.intValue());
        });
        long total = army.values().stream().mapToLong(Integer::longValue).sum();
        if (level >= 2) {
            out.put("branches", army.keySet().stream().map(id -> UnitDef.UNITS.get(id).branch()).distinct().sorted().toList());
            long lower = 1;
            String band = "未知";
            for (long upper : new long[]{100, 500, 1000, 5000, 10000, 50000, 100000, 500000, Long.MAX_VALUE}) {
                if (total > 0 && total <= upper) { band = upper == Long.MAX_VALUE ? lower + "以上" : lower + "—" + upper; break; }
                lower = upper + 1;
            }
            out.put("strengthBand", band);
        }
        if (level >= 3) { out.put("unitTypes", new ArrayList<>(army.keySet())); out.put("totalTroops", total); }
        if (level >= 4) { if (raw.containsKey("army")) out.put("army", army); copy(raw, out, "commander", "commanderStar", "commanderLevel"); }
        if (level >= 5) copy(raw, out, "combatTech", "unitEquipment", "skills", "commandBonus", "commanderAttributes", "intelligenceAt", "intelligenceSource");
        return out;
    }

    private static void copy(Map<String, Object> from, Map<String, Object> to, String... keys) {
        for (String key : keys) if (from.containsKey(key)) to.put(key, from.get(key));
    }
}
