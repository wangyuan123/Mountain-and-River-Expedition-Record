package com.wargame.service.pvp;

import com.wargame.model.constants.*;
import com.wargame.model.dto.CommandBonus;
import com.wargame.model.dto.pvp.PvpSnapshot;
import com.wargame.model.entity.PvpPowerCalibration;
import com.wargame.repository.PvpPowerCalibrationRepository;
import com.wargame.service.BattleService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.core.type.TypeReference;

/** 技能组合整体校准并写入版本化表；匹配扫描只读票据P，不运行模拟。 */
@Service
@RequiredArgsConstructor
public class PvpPowerModel {
    public static final String VERSION = "power-v1-" + PvpSnapshotService.RULES;
    public static final Set<String> COMBAT_SKILLS = com.wargame.model.constants.OfficerSkillDef.combatSkills();
    /** 两种战场均使用五个固定种子，使每条配置至少包含5000场实际对照战斗。 */
    public static final List<Long> CALIBRATION_SEEDS = List.of(71001L, 71002L, 71003L, 71004L, 71005L);
    private final PvpPowerCalibrationRepository calibrations;
    private final JdbcTemplate jdbc;
    private static final Map<String, Map<String, Object>> MEASURED = loadMeasured();

    /** 版本化实测配置随构建发布；不兼容或损坏时禁止用旧系数创建新快照。 */
    private static Map<String, Map<String, Object>> loadMeasured() {
        try (var stream = PvpPowerModel.class.getResourceAsStream("/game/pvp-power-calibration-v1.json")) {
            if (stream == null) throw new IllegalStateException("缺少演习实力校准配置");
            List<Map<String, Object>> rows = PvpJson.read(new String(stream.readAllBytes(), StandardCharsets.UTF_8), new TypeReference<>() {});
            Map<String, Map<String, Object>> measured = new HashMap<>();
            for (var row : rows) {
                // 战斗周期更新后，旧系数不可复用；未附带本版实测的组合由入队流程重新校准。
                if (!VERSION.equals(row.get("modelVersion"))) continue;
                double factor = ((Number) row.get("factor")).doubleValue();
                if (!Double.isFinite(factor) || factor <= 0
                        || ((Number) row.get("samples")).intValue() <= 0) throw new IllegalStateException("演习实力校准配置版本或样本无效");
                measured.put(row.get("field") + ":" + PvpJson.write(row.get("skills")), Map.copyOf(row));
            }
            return Map.copyOf(measured);
        } catch (IOException e) { throw new IllegalStateException("演习实力校准配置读取失败", e); }
    }

    /** 新组合第一次入队前完成对照实验，后续读取不可变校准表；不持有真实账号锁。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PvpSnapshot estimate(PvpSnapshot snapshot) {
        Map<String, Integer> skills = new TreeMap<>();
        snapshot.skills().forEach((id, n) -> { if (COMBAT_SKILLS.contains(id) && n > 0) skills.put(id, Math.min(5, n)); });
        String signature = snapshot.battlefield() + ":" + PvpJson.write(skills);
        String id = PvpJson.hash(VERSION + signature);
        PvpPowerCalibration row = calibrations.findById(id).orElse(null);
        if (row == null) {
            Map<String, Object> measured = MEASURED.get(signature);
            if (measured == null) measured = calibrate(snapshot.battlefield(), skills);
            double factor = ((Number) measured.get("factor")).doubleValue();
            // 唯一内容哈希使多实例计算同一组合时只保存一行；样本与种子完全相同。
            jdbc.update("INSERT IGNORE INTO pvp_power_calibrations(id,model_version,signature,factor,evidence,version) VALUES (?,?,?,?,?,0)",
                    id, VERSION, signature, factor, PvpJson.write(measured));
            row = calibrations.findById(id).orElseThrow();
        }
        double value = basePower(snapshot) * row.getFactor();
        if (!Double.isFinite(value) || value <= 0 || value > Long.MAX_VALUE / 2.0) throw new IllegalArgumentException("演习实力估值溢出");
        return snapshot.withPower(Math.max(1, Math.round(value)), row.getFactor(), VERSION);
    }

    /** 不含技能组合系数的阵容估值，用于固定种子对照验证，绝不进入战斗伤害。 */
    public static double basePower(PvpSnapshot snapshot) {
        double value = 0;
        for (var e : snapshot.army().entrySet()) {
            var s = snapshot.effectiveStats().get(e.getKey());
            double attack = .7 * s.get("SEA_AIR".equals(snapshot.battlefield()) ? "sea" : "ground") + .3 * s.get("air");
            double v = Math.sqrt(Math.max(1, attack) * s.get("hp")) * (1 + s.get("defense") / 100);
            UnitDef u = UnitDef.UNITS.get(e.getKey());
            double mobility = Boolean.TRUE.equals(u.logistic()) ? 1 : 1 + Math.min(.20, s.get("range") / 10000) + Math.min(.10, s.get("speed") / 100);
            value += e.getValue() * v * mobility;
        }
        return value;
    }

    /**
     * 比较整个技能组合相对无技能的等量表现，再寻找数量折扣使参考集合总体表现相近。
     * 覆盖标准阵容、两种规模/科技/配装、固定种子和左右互换；系数不是单技能胜率贡献。
     */
    public static Map<String, Object> calibrate(String field, Map<String, Integer> skills) {
        if (skills.isEmpty()) return Map.of("factor", 1.0, "samples", 0, "reason", "NO_COMBAT_SKILL", "modelVersion", VERSION);
        List<Map<String, Integer>> formations = formations(field);
        double baseline = performance(field, formations, Map.of(), 1);
        double raw = performance(field, formations, skills, 1);
        double lo = .5, hi = 1.5;
        // 条件技能可能在参考阵容中完全不触发，必须保留等量实测点，不能凭二分误差制造负收益。
        double scale = 1, residual = raw - baseline;
        int measurements = 2;
        for (int i = 0; i < 7; i++) {
            double mid = (lo + hi) / 2;
            double difference = performance(field, formations, skills, mid) - baseline;
            measurements++;
            if (Math.abs(difference) < Math.abs(residual)) { scale = mid; residual = difference; }
            if (difference > 0) hi = mid; else lo = mid;
        }
        // 兵力取整使战果不连续；保留残差最小的真实测点，必要时细化，避免直接采用区间中点。
        for (int i = 0; i < 5; i++) {
            double mid = (lo + hi) / 2;
            double difference = performance(field, formations, skills, mid) - baseline;
            measurements++;
            if (Math.abs(difference) < Math.abs(residual)) { scale = mid; residual = difference; }
            if (Math.abs(residual) < .02) break;
            if (difference > 0) hi = mid; else lo = mid;
        }
        return Map.of("factor", 1 / scale, "quantityScale", scale, "baseline", baseline, "skillScore", raw,
                "residual", residual, "samples", formations.size() * formations.size() * 2 * 2 * CALIBRATION_SEEDS.size() * 2 * measurements,
                "seeds", CALIBRATION_SEEDS, "modelVersion", VERSION,
                "method", "mean weighted loss difference; 7-step quantity bisection with up to 5 refinement steps; best measured residual including equal quantities; mirrored A/B; scale 100/1000; tech 0/5; military/defense 60");
    }

    public static List<Map<String, Integer>> formations(String field) {
        return "SEA_AIR".equals(field) ? List.of(Map.of("destroyer", 2), Map.of("sub", 2), Map.of("battleship", 1, "carrier", 1), Map.of("fighter", 2, "bomber", 1))
                : List.of(Map.of("infantry", 4), Map.of("ltank", 2, "htank", 1), Map.of("rocket", 2, "armored", 1), Map.of("fighter", 2, "bomber", 1), Map.of("infantry", 3, "ltank", 1, "rocket", 1, "fighter", 1));
    }

    private static double performance(String field, List<Map<String, Integer>> forms, Map<String, Integer> skills, double quantity) {
        double sum = 0; int samples = 0;
        for (var own : forms) for (var foe : forms) for (int scale : new int[]{100, 1000}) for (int tech : new int[]{0, 5})
            for (long seed : CALIBRATION_SEEDS) for (boolean mirror : new boolean[]{false, true}) {
                Map<String, Integer> a = sized(own, scale, quantity, field), b = sized(foe, scale, 1, field);
                var ac = context(a, skills, tech); var bc = context(b, Map.of(), tech);
                var result = simulate(seed, mirror ? b : a, mirror ? a : b, mirror ? bc : ac, mirror ? ac : bc);
                double ownLoss = loss(a, mirror ? result.b : result.a, field), enemyLoss = loss(b, mirror ? result.a : result.b, field);
                sum += enemyLoss - ownLoss; samples++;
            }
        return sum / samples;
    }
    private static Map<String, Integer> sized(Map<String, Integer> form, int scale, double multiplier, String field) {
        double totalValue = form.entrySet().stream().mapToDouble(e -> e.getValue() * PvpSnapshotService.baseValue(e.getKey(), field)).sum();
        Map<String, Integer> out = new TreeMap<>();
        form.forEach((id, n) -> out.put(id, Math.max(1, (int) Math.round(n * scale * 100 * multiplier / totalValue))));
        return out;
    }
    private static BattleService.TechCtx context(Map<String, Integer> army, Map<String, Integer> skills, int level) {
        Map<String, Integer> tech = new HashMap<>();
        // 校准参考成长同时包含生命与其对应的火力科技，避免沿用旧输出节奏的技能系数。
        for (String key : List.of("attack_tech", "defense_tech", "cmd_hp", "fire_density", "weapon_range", "arm_engine", "air_engine", "nav_engine")) tech.put(key, level);
        return new BattleService.TechCtx(tech, skills, 60, 60, CommandBonus.NONE);
    }
    private record Outcome(Map<String, Integer> a, Map<String, Integer> b) {}
    private static Outcome simulate(long seed, Map<String, Integer> initialA, Map<String, Integer> initialB, BattleService.TechCtx ac, BattleService.TechCtx bc) {
        Map<String, Integer> a = initialA, b = initialB, ap = new TreeMap<>(), bp = new TreeMap<>();
        BattleService engine = new BattleService(seed, false);
        int d = engine.calcInitialDistance(a, b, ac, bc), first = 0;
        for (String id : a.keySet()) ap.put(id, 0); for (String id : b.keySet()) bp.put(id, d);
        for (int round = 1; round <= 24; round++) {
            var r = engine.resolvePracticeRound(a, b, ap, bp, d, ac, bc, round, first, Map.of(), Map.of());
            a = r.attackerArmy(); b = r.defenderArmy(); ap = new TreeMap<>(r.attackerPositions()); bp = new TreeMap<>(r.defenderPositions()); first = r.firstCombatRound();
            if (r.finished()) break;
        }
        return new Outcome(a, b);
    }
    public static double loss(Map<String, Integer> initial, Map<String, Integer> current, String field) {
        double all = 0, lost = 0;
        for (var e : initial.entrySet()) { double value = PvpSnapshotService.baseValue(e.getKey(), field); all += value * e.getValue(); lost += value * Math.max(0, e.getValue() - current.getOrDefault(e.getKey(), 0)); }
        return all == 0 ? 0 : Math.max(0, Math.min(1, lost / all));
    }
}
