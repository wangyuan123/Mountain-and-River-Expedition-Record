package com.wargame.service.pvp;

import com.wargame.util.GameClock;
import com.wargame.model.constants.*;
import com.wargame.model.dto.CommandBonus;
import com.wargame.model.dto.pvp.*;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** 只读取真实数据；复制完成即释放账号锁，不占用部队、军官或行军槽。 */
@Service
@RequiredArgsConstructor
public class PvpSnapshotService {
    public static final String RULES = "pvp-v1-" + PvpJson.hash(PvpJson.write(Map.of("battle", BattleRules.PRACTICE_VERSION, "units", versionedUnits(),
            "skills", versionedSkills(), "equipment", UnitEquipmentDef.ITEMS))).substring(0, 24);

    /** 城市协防只补充闪电的场景说明，演习仍用原引擎及已校准的技能定义摘要。 */
    private static Map<String, OfficerSkillDef> versionedSkills() {
        Map<String, OfficerSkillDef> skills = new TreeMap<>(OfficerSkillDef.OFFICER_SKILLS);
        var blitz = skills.get("blitz");
        skills.put("blitz", new OfficerSkillDef(blitz.key(), blitz.name(),
                "首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效），战场移动速度+6%/级，最高30%，仅本回合生效；首次交战仅影响同射程单位的交火先手，后续生效回合同时影响机动与先手。行动顺序按射程优先、同射程速度优先；射程与速度均相同时，奇数回合进攻方先手、偶数回合防守方先手", blitz.max(), blitz.cat()));
        return skills;
    }

    /** 生产设施调整不改变战斗：摘要保留历史建筑归属，避免作废实测校准及进行中的演习。 */
    private static com.fasterxml.jackson.databind.JsonNode versionedUnits() {
        // 使用 JSON 树保留 record 字段的序列，沿用已发布摘要的序列化顺序。
        var units = PvpJson.read(PvpJson.write(UnitDef.UNITS), com.fasterxml.jackson.databind.JsonNode.class);
        for (var unit : units) {
            // 兵种改名只影响文案；仍按既有名称校验演习快照，真实战斗属性继续参与摘要。
            if ("rocket".equals(unit.path("key").asText())) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) unit).put("name", "反坦克火箭车-喀秋莎（BM-13）");
            }
            // 仅归一化调整过的生产建筑元数据，其余战斗、成本与人口参数仍参与版本校验。
            if ("sea".equals(unit.path("branch").asText())) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) unit).put("build", "port");
            } else if ("scout".equals(unit.path("key").asText())) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) unit).put("build", "factory");
            }
        }
        return units;
    }
    private final PlayerRepository players;
    private final PlayerCityRepository cities;
    private final OfficerRepository officers;
    private final MarchRepository marches;
    private final MarchTargetService targets;
    private final EquipmentService equipment;
    private final ArmyService armies;
    private final BattleService engine;
    private final CityScope scope;

    public static Map<String, Integer> quantities(Map<String, Object> raw) {
        if (raw == null || raw.size() > UnitDef.UNITS.size()) throw new IllegalArgumentException("请选择合法编队");
        Map<String, Integer> out = new TreeMap<>();
        raw.forEach((id, value) -> {
            if (!UnitDef.UNITS.containsKey(id) || !(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                    || n.doubleValue() != n.longValue() || n.longValue() < 0 || n.longValue() > Integer.MAX_VALUE)
                throw new IllegalArgumentException("兵力须为已知兵种的非负整数");
            if (n.intValue() > 0) out.put(id, n.intValue());
        });
        return out;
    }

    /** 创建不可变副本；真实账户锁和城市上下文只保留在本次短事务。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PvpSnapshot freeze(Long playerId, PvpPreset preset) {
        Player p = players.lockById(playerId).orElseThrow();
        if (!p.accountActive()) throw new IllegalArgumentException("账号不可用");
        requireCity(playerId, preset.citySlot());
        if (!Set.of("LAND_AIR", "SEA_AIR").contains(preset.battlefield())) throw new IllegalArgumentException("未知战场类别");
        try (var ignored = scope.enter(playerId, preset.citySlot())) {
            Map<String, Integer> available = targets.getArmyMap(playerId);
            long total = 0; boolean attacks = false;
            for (var e : preset.army().entrySet()) {
                UnitDef u = UnitDef.UNITS.get(e.getKey());
                if (u == null || e.getValue() == null || e.getValue() <= 0 || e.getValue() > available.getOrDefault(e.getKey(), 0))
                    throw new IllegalArgumentException("兵种或数量超过来源城市可用驻军");
                if ("sea".equals(u.branch()) && !"SEA_AIR".equals(preset.battlefield())
                        || "land".equals(u.branch()) && !"LAND_AIR".equals(preset.battlefield()))
                    throw new IllegalArgumentException("陆军与海军不能混编");
                total += e.getValue();
                if (!Boolean.TRUE.equals(u.logistic()) && (u.atkAir() > 0 || ("SEA_AIR".equals(preset.battlefield()) ? u.atkSea() : u.atkGround()) > 0)) attacks = true;
            }
            if (!attacks || total == 0) throw new IllegalArgumentException("至少需要一个有攻击能力的战斗兵种");
            if (total > armies.armyCap(playerId)) throw new IllegalArgumentException("编队超过本城带兵上限");
            Officer o = preset.commanderId() == null ? null : targets.getOfficerById(playerId, preset.commanderId());
            if (preset.commanderId() != null && (o == null || !Objects.equals(o.getCitySlot(), preset.citySlot())
                    || Set.of("mayor", "commander", "commander_in_chief").contains(Objects.toString(o.getRole(), ""))
                    || marches.existsByPlayerIdAndCommanderId(playerId, o.getId())))
                throw new IllegalArgumentException("请选择本城未任职且未出征的带兵将领");
            var attrs = equipment.attributes(o);
            Officer chief = targets.getCommanderInChief(playerId, preset.citySlot());
            var ca = equipment.attributes(chief);
            CommandBonus bonus = chief == null ? CommandBonus.NONE : new CommandBonus(chief.getId(), chief.getName(), ca.military(), ca.defense(), ca.logistics(), ca.knowledge());
            Map<String, Integer> tech = new TreeMap<>(UnitEquipmentEffects.forArmy(targets.getCombatTechMap(playerId), preset.army()));
            tech.keySet().removeIf(k -> !k.startsWith(UnitEquipmentEffects.PREFIX) && !Set.of("attack_tech", "defense_tech", "cmd_hp", "fire_density", "arm_engine", "air_engine", "nav_engine", "weapon_range").contains(k));
            Map<String, Integer> skills = new TreeMap<>(targets.getCommanderSkills(o));
            Map<String, Double> values = new TreeMap<>();
            Map<String, Map<String, Double>> stats = new TreeMap<>();
            var ctx = new BattleService.TechCtx(tech, skills, attrs.military(), attrs.defense(), bonus);
            preset.army().forEach((id, n) -> { values.put(id, baseValue(id, preset.battlefield())); stats.put(id, engine.practiceStats(id, ctx)); });
            Map<String, BattleService.UnitOrder> defaults = new TreeMap<>();
            for (String id : preset.army().keySet()) {
                var order = preset.defaults() == null ? null : preset.defaults().get(id);
                defaults.put(id, order == null ? new BattleService.UnitOrder(Boolean.FALSE.equals(UnitDef.UNITS.get(id).autoAdvance()) ? BattleService.CommandAction.HOLD : BattleService.CommandAction.ADVANCE) : order);
            }
            Object gear = attrs.equipment().stream().map(e -> Map.of("slot", Objects.toString(e.getSlot(), ""), "item", Objects.toString(e.getItemKey(), ""))).toList();
            return new PvpSnapshot(UUID.randomUUID().toString(), Objects.toString(p.getDisplayName(), p.getUsername()), preset.citySlot(), preset.battlefield(),
                    new TreeMap<>(preset.army()), tech, skills, o == null ? null : o.getId(), o == null ? "无带兵将领" : o.getName(),
                    attrs.military(), attrs.defense(), attrs.logistics(), attrs.knowledge(), gear, bonus, defaults, values, stats, RULES, "", 0, 1);
        }
    }

    public static double baseValue(String id, String field) {
        UnitDef u = UnitDef.UNITS.get(id);
        double attack = .7 * ("SEA_AIR".equals(field) ? u.atkSea() : u.atkGround()) + .3 * u.atkAir();
        return Math.sqrt(Math.max(1, attack) * u.hp()) * (1 + u.def() / 100);
    }

    private void requireCity(Long player, int slot) {
        if (slot < 0 || slot > 100) throw new IllegalArgumentException("来源城市无效");
        var c = cities.findByOwnerIdAndCitySlot(player, slot);
        if (slot != 0 && c.isEmpty() || c.isPresent() && c.get().getReadyAt() > GameClock.now()) throw new IllegalArgumentException("城市不存在或尚未建成");
    }

    /** 大厅展示当前合法候选和驻军，仅返回本人资料。 */
    @Transactional(readOnly = true)
    public Map<String, Object> available(Long player, int slot) {
        requireCity(player, slot);
        try (var ignored = scope.enter(player, slot)) {
            var candidates = officers.findByPlayerIdAndCitySlot(player, slot).stream()
                    .filter(o -> !Set.of("mayor", "commander", "commander_in_chief").contains(Objects.toString(o.getRole(), "")))
                    .filter(o -> !marches.existsByPlayerIdAndCommanderId(player, o.getId()))
                    .map(o -> Map.of("id", o.getId(), "name", o.getName(), "attributes", equipment.attributes(o), "skills", targets.getCommanderSkills(o))).toList();
            List<Map<String, Object>> cityList = new ArrayList<>();
            cityList.add(Map.of("slot", 0, "name", "主城"));
            cities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(player).stream().filter(c -> c.getCitySlot() != 0 && c.getReadyAt() <= GameClock.now())
                    .forEach(c -> cityList.add(Map.of("slot", c.getCitySlot(), "name", "城市 " + c.getCitySlot())));
            return Map.of("army", targets.getArmyMap(player), "cap", armies.armyCap(player), "officers", candidates, "cities", cityList,
                    "chief", Objects.requireNonNullElse(targets.getCommanderInChief(player, slot), new Officer()).getName() == null ? "未任命" : targets.getCommanderInChief(player, slot).getName());
        }
    }
}
