package com.wargame.service;

import com.wargame.model.constants.MilitaryRankDef;
import com.wargame.model.constants.UnitDef;
import com.wargame.model.constants.UnitEquipmentDef;
import com.wargame.model.constants.UnitEquipmentEffects;
import com.wargame.model.dto.CommandBonus;
import com.wargame.model.entity.OfficerEquipment;
import com.wargame.util.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/** 全账号静态估值；驻军与行军分别计数，伤兵和生产队列不计入。 */
@Service
public class PlayerPowerService {
    @org.springframework.beans.factory.annotation.Autowired(required = false) private PlayerPowerHistoryService powerHistory;
    public record Scores(long formationPower, long overallPower) {}
    private record Officer(int city, String role, int level, int star, int military, int defense, int logistics, int knowledge, String skills) {}
    private record MarchArmy(Map<String, Integer> units, Long commander, int city, boolean pooled) {}
    private static final class Assets {
        boolean deleted;
        int rank = 1;
        final Map<Integer, Map<String, Integer>> cities = new HashMap<>();
        final List<MarchArmy> marches = new ArrayList<>();
        final Map<Long, Officer> officers = new HashMap<>();
        final Map<String, Integer> tech = new HashMap<>();
        final Map<String, Map<String, String>> loadouts = new HashMap<>();
        final Map<Long, List<OfficerEquipment>> officerEquipment = new HashMap<>();
        final Map<String, Integer> preparation = new HashMap<>();
        final Map<Long, EquipmentService.Attributes> attributes = new HashMap<>();
        final Set<Integer> headquarters = new HashSet<>();
        long development;
    }

    private final JdbcTemplate jdbc;
    private final BattleService battle;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    public PlayerPowerService(JdbcTemplate jdbc, BattleService battle) {
        this.jdbc = jdbc;
        this.battle = battle;
    }

    /** playerId 为 null 时批量读取全服，避免排行榜逐人查询。 */
    public Map<Long, Scores> calculate(Long playerId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) entityManager.flush();
        Map<Long, Assets> all = new HashMap<>();
        query("SELECT id, military_rank, account_status FROM players", playerId, "id", rs -> {
            Assets assets = new Assets();
            assets.deleted = "DELETED".equals(rs.getString("account_status"));
            assets.rank = Math.max(1, rs.getInt("military_rank"));
            all.put(rs.getLong("id"), assets);
        });
        query("SELECT player_id, city_slot, type, count FROM army_units", playerId, "player_id", rs -> {
            Assets a = all.get(rs.getLong("player_id"));
            if (a != null && rs.getInt("count") > 0) a.cities.computeIfAbsent(rs.getInt("city_slot"), k -> new HashMap<>())
                    .merge(rs.getString("type"), rs.getInt("count"), Integer::sum);
        });
        query("""
                SELECT m.player_id, m.city_slot, COALESCE(s.attacker_army, m.army) AS army, m.commander_id
                FROM marches m LEFT JOIN battle_sessions s ON s.march_id = m.id
                """, playerId, "m.player_id", rs -> {
            Assets a = all.get(rs.getLong("player_id"));
            if (a != null) {
                long commander = rs.getLong("commander_id");
                Long commanderId = rs.wasNull() ? null : commander;
                a.marches.add(new MarchArmy(JsonUtil.parseIntMap(rs.getString("army")), commanderId, rs.getInt("city_slot"), false));
            }
        });
        query("SELECT occupied_by, occupied_city_slot, garrison FROM wild_tiles WHERE occupied = true", playerId, "occupied_by", rs -> {
            Assets a = all.get(rs.getLong("occupied_by"));
            if (a != null) a.marches.add(new MarchArmy(JsonUtil.parseIntMap(rs.getString("garrison")), null, rs.getInt("occupied_city_slot"), true));
        });
        query("SELECT id, player_id, city_slot, role, level, star, military, defense, logistics, knowledge, skills FROM officers", playerId, "player_id", rs -> {
            Assets a = all.get(rs.getLong("player_id"));
            if (a != null) a.officers.put(rs.getLong("id"), new Officer(rs.getInt("city_slot"), rs.getString("role"),
                    rs.getInt("level"), rs.getInt("star"), rs.getInt("military"), rs.getInt("defense"),
                    rs.getInt("logistics"), rs.getInt("knowledge"), rs.getString("skills")));
        });
        query("SELECT player_id, type, level FROM technologies", playerId, "player_id", rs -> {
            Assets a = all.get(rs.getLong("player_id"));
            if (a != null) a.tech.merge(rs.getString("type"), Math.max(0, rs.getInt("level")), Math::max);
        });
        query("SELECT player_id, unit_type, slots FROM unit_loadouts", playerId, "player_id", rs -> {
            Assets a = all.get(rs.getLong("player_id"));
            if (a == null) return;
            String unit = rs.getString("unit_type");
            Map<String, String> slots = new HashMap<>();
            JsonUtil.parseObjMap(rs.getString("slots")).forEach((slot, item) -> {
                UnitEquipmentDef def = UnitEquipmentDef.ITEMS.get(String.valueOf(item));
                if (def != null && def.unit().equals(unit) && def.slot().equals(slot)) slots.put(slot, def.key());
            });
            a.loadouts.put(unit, slots);
        });
        query("SELECT player_id, city_slot, type, level FROM buildings", playerId, "player_id", rs -> {
            Assets a = all.get(rs.getLong("player_id"));
            if (a != null) {
                int level = Math.max(0, rs.getInt("level"));
                a.development += 100L * level;
                if (level > 0 && "headquarters".equals(rs.getString("type"))) a.headquarters.add(rs.getInt("city_slot"));
            }
        });
        query("SELECT id, player_id, officer_id, item_key, slot, military_bonus, defense_bonus, logistics_bonus, knowledge_bonus FROM officer_equipment", playerId, "player_id", rs -> {
            Assets a = all.get(rs.getLong("player_id"));
            if (a != null) {
                int[] bonuses = {Math.max(0, rs.getInt("military_bonus")), Math.max(0, rs.getInt("defense_bonus")),
                        Math.max(0, rs.getInt("logistics_bonus")), Math.max(0, rs.getInt("knowledge_bonus"))};
                long officerId = rs.getLong("officer_id");
                if (!rs.wasNull()) {
                    for (int bonus : bonuses) a.development += 10L * bonus;
                    OfficerEquipment equipped = new OfficerEquipment();
                    equipped.setId(rs.getLong("id"));
                    equipped.setOfficerId(officerId);
                    equipped.setItemKey(rs.getString("item_key"));
                    equipped.setSlot(rs.getString("slot"));
                    equipped.setMilitaryBonus(bonuses[0]);
                    equipped.setDefenseBonus(bonuses[1]);
                    equipped.setLogisticsBonus(bonuses[2]);
                    equipped.setKnowledgeBonus(bonuses[3]);
                    a.officerEquipment.computeIfAbsent(officerId, key -> new ArrayList<>()).add(equipped);
                }
            }
        });
        query("SELECT player_id, progress_key, amount FROM officer_equipment_progress WHERE progress_key LIKE 'prep/%'", playerId, "player_id", rs -> {
            Assets a = all.get(rs.getLong("player_id"));
            if (a != null) a.preparation.put(rs.getString("progress_key"), Math.max(0, rs.getInt("amount")));
        });
        Map<Long, Scores> result = new HashMap<>();
        all.forEach((id, assets) -> result.put(id, score(assets)));
        result.forEach((id, scores) -> { if (!all.get(id).deleted && powerHistory != null) powerHistory.record(id, scores); });
        return result;
    }

    private void query(String sql, Long playerId, String column, org.springframework.jdbc.core.RowCallbackHandler handler) {
        if (playerId == null) jdbc.query(sql, handler);
        else jdbc.query(sql + (sql.contains(" WHERE ") ? " AND " : " WHERE ") + column + " = ?", handler, playerId);
    }

    private Scores score(Assets assets) {
        assets.officers.forEach((id, officer) -> {
            var base = new com.wargame.model.entity.Officer();
            base.setMilitary(officer.military());
            base.setDefense(officer.defense());
            base.setLogistics(officer.logistics());
            base.setKnowledge(officer.knowledge());
            Map<String, Integer> preparation = new HashMap<>();
            String prefix = "prep/" + id + "/";
            assets.preparation.forEach((key, value) -> {
                if (key.startsWith(prefix)) preparation.put(key.substring(prefix.length()), value);
            });
            assets.attributes.put(id, EquipmentService.calculateAttributes(base,
                    assets.officerEquipment.getOrDefault(id, List.of()), preparation));
        });
        Map<String, Integer> combat = new HashMap<>(assets.tech);
        combat.putAll(UnitEquipmentEffects.snapshot(assets.loadouts));
        double total = 0, best = 0;
        for (Map.Entry<Integer, Map<String, Integer>> city : assets.cities.entrySet()) {
            Long commander = officerId(assets, city.getKey(), "commander");
            Long chief = assets.headquarters.contains(city.getKey()) ? officerId(assets, city.getKey(), "commander_in_chief") : null;
            int leadership = skillLevel(assets.officers.get(commander), "leadership");
            if (leadership == 0) leadership = skillLevel(assets.officers.get(commander), "supply");
            int rankBase = MilitaryRankDef.getRankBase(assets.rank);
            long cap = (long) Math.floor(rankBase * (1 + 0.04 * leadership)) + rankBase / 2L;
            total += armyValue(city.getValue(), combat, commander, chief, assets, Long.MAX_VALUE);
            best = Math.max(best, formationValue(city.getValue(), combat, commander, chief, assets, cap));
        }
        for (MarchArmy march : assets.marches) {
            Long chief = !march.pooled() && assets.headquarters.contains(march.city())
                    ? officerId(assets, march.city(), "commander_in_chief") : null;
            total += armyValue(march.units(), combat, march.commander(), chief, assets, Long.MAX_VALUE);
            int rankBase = MilitaryRankDef.getRankBase(assets.rank);
            long cap = march.pooled() ? rankBase + rankBase / 2L : Long.MAX_VALUE;
            best = Math.max(best, formationValue(march.units(), combat, march.commander(), chief, assets, cap));
        }
        long development = assets.development + assets.tech.values().stream().mapToLong(level -> 500L * level).sum();
        for (Officer officer : assets.officers.values()) {
            development += 100L * Math.max(0, officer.level()) + 500L * Math.max(0, officer.star())
                    + 10L * (Math.max(0, officer.military()) + Math.max(0, officer.defense())
                    + Math.max(0, officer.logistics()) + Math.max(0, officer.knowledge()));
            for (Map<String, Object> skill : JsonUtil.parseList(officer.skills())) {
                if (skill.get("lv") instanceof Number level) development += 200L * Math.max(0, level.longValue());
            }
        }
        return new Scores(Math.round(best), Math.round(total) + development);
    }

    private double formationValue(Map<String, Integer> army, Map<String, Integer> tech, Long commander, Long chief, Assets assets, long cap) {
        double best = 0;
        for (String branch : List.of("land", "sea")) {
            Map<String, Integer> legal = new HashMap<>();
            army.forEach((unit, count) -> {
                UnitDef def = UnitDef.UNITS.get(UnitEquipmentEffects.base(unit));
                if (def != null && (branch.equals(def.branch()) || "air".equals(def.branch()))) legal.put(unit, count);
            });
            best = Math.max(best, armyValue(legal, tech, commander, chief, assets, cap));
        }
        return best;
    }

    private Long officerId(Assets assets, int city, String role) {
        return assets.officers.entrySet().stream().filter(entry -> entry.getValue().city() == city && role.equals(entry.getValue().role()))
                .map(Map.Entry::getKey).min(Long::compareTo).orElse(null);
    }

    private int skillLevel(Officer officer, String skillId) {
        if (officer == null) return 0;
        return JsonUtil.parseList(officer.skills()).stream().filter(skill -> skillId.equals(skill.get("id")))
                .mapToInt(skill -> skill.get("lv") instanceof Number level ? Math.max(0, level.intValue()) : 0).findFirst().orElse(0);
    }

    /** 按战斗属性估算单兵价值；驻军取军衔出征上限内价值最高的兵种。 */
    private double armyValue(Map<String, Integer> army, Map<String, Integer> tech, Long commanderId, Long chiefId, Assets assets, long cap) {
        Officer commander = assets.officers.get(commanderId);
        EquipmentService.Attributes officer = assets.attributes.get(commanderId);
        EquipmentService.Attributes chief = assets.attributes.get(chiefId);
        CommandBonus chiefBonus = chief == null ? CommandBonus.NONE : new CommandBonus(chiefId, "", chief.military(),
                chief.defense(), chief.logistics(), chief.knowledge());
        BattleService.TechCtx ctx = new BattleService.TechCtx(tech, Map.of(),
                officer == null ? 0 : Math.max(0, officer.military()),
                officer == null ? 0 : Math.max(0, officer.defense()), chiefBonus);
        double attackSkill = 1 + 0.1 * skillLevel(commander, "frenzy") / 3.0;
        double defenseSkill = 1 + 0.1 * skillLevel(commander, "bulwark") / 3.0;
        List<Map.Entry<Double, Integer>> values = new ArrayList<>();
        army.forEach((id, count) -> {
            String base = UnitEquipmentEffects.base(id);
            if (!UnitDef.UNITS.containsKey(base) || count == null || count <= 0) return;
            Map<String, Double> stats = battle.practiceStats(base, ctx);
            double attack = Math.max(stats.get("ground"), Math.max(stats.get("air"), stats.get("sea")));
            double value = Math.sqrt(Math.max(1, attack * attackSkill) * Math.max(1, stats.get("hp")))
                    * (1 + stats.get("defense") * defenseSkill / 100);
            values.add(Map.entry(value, count));
        });
        values.sort(Map.Entry.<Double, Integer>comparingByKey().reversed());
        double result = 0;
        for (var entry : values) {
            long count = Math.min(cap, entry.getValue());
            result += entry.getKey() * count;
            cap -= count;
            if (cap == 0) break;
        }
        return result;
    }
}
