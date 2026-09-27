package com.wargame.service;

import com.wargame.model.constants.UnitDef;
import com.wargame.model.entity.Player;
import com.wargame.repository.PlayerRepository;
import com.wargame.repository.MarchRepository;
import com.wargame.repository.BattleSessionRepository;
import com.wargame.repository.PlayerCityRepository;
import com.wargame.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** 玩家账号级兵种默认战术；离线结算也使用服务端保存的配置。 */
@Service
public class BattleActionPreferences {
    private final PlayerRepository players;
    private final ArmyService armyService;
    private final MarchRepository marches;
    private final BattleSessionRepository battleSessions;
    private final PlayerCityRepository cities;

    public BattleActionPreferences(PlayerRepository players, ArmyService armyService, MarchRepository marches,
                                   BattleSessionRepository battleSessions, PlayerCityRepository cities) {
        this.players = players;
        this.armyService = armyService;
        this.marches = marches;
        this.battleSessions = battleSessions;
        this.cities = cities;
    }

    /** sortieArmy 为 null 时自动选兵；手动配置允许缺省兵种，缺省值为 0。 */
    public record Preferences(Map<String, String> outgoing, Map<String, String> defending,
                              Map<String, Object> sortieArmy) {}

    public record View(Map<String, String> outgoing, Map<String, String> defending,
                       Map<String, Integer> sortieArmy, int sortieCap, boolean locked) {}

    /** 战斗行军从派出到到达均锁定进攻方；守城只在城市进入实际战斗会话后锁定。 */
    @Transactional(readOnly = true)
    public boolean isLocked(Long playerId) {
        if (marches.existsActiveAttack(playerId) || battleSessions.existsByPlayerId(playerId)) return true;
        List<String> cityIds = cities.findByOwnerId(playerId).stream()
                .map(city -> String.valueOf(city.getId())).toList();
        return !cityIds.isEmpty() && battleSessions.existsDefendingCityBattle(cityIds);
    }

    /** 返回所有兵种的有效默认行为，未自定义时沿用兵种配置。 */
    @Transactional(readOnly = true)
    public View get(Long playerId) {
        Player player = players.findById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        return new View(effective(player.getOutgoingBattleActions()), effective(player.getDefendingBattleActions()),
                player.getSortieArmy() == null ? null : JsonUtil.parseIntMap(player.getSortieArmy()),
                armyService.sortieCap(playerId), isLocked(playerId));
    }

    /** 保存攻守两套完整配置；拒绝未知兵种或非法命令，防止离线回合使用无效指令。 */
    @Transactional
    public View save(Long playerId, Preferences preferences) {
        if (isLocked(playerId)) throw new IllegalArgumentException("战斗或进攻行军期间无法修改默认战术");
        if (preferences == null) {
            throw new IllegalArgumentException("请同时提交出城和守城的默认战术");
        }
        Map<String, String> outgoing = validate(preferences.outgoing());
        Map<String, String> defending = validate(preferences.defending());
        Map<String, Integer> sortieArmy = validateSortieArmy(preferences.sortieArmy(), armyService.sortieCap(playerId));
        Player player = players.findById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        player.setOutgoingBattleActions(JsonUtil.toJson(outgoing));
        player.setDefendingBattleActions(JsonUtil.toJson(defending));
        player.setSortieArmy(sortieArmy == null ? null : JsonUtil.toJson(sortieArmy));
        players.save(player);
        return get(playerId);
    }

    /** 校验真实整数及总上限；不将负数、小数、未知兵种或溢出值静默转换成有效配置。 */
    private Map<String, Integer> validateSortieArmy(Map<String, Object> configured, int cap) {
        if (configured == null) return null;
        Map<String, Integer> result = new LinkedHashMap<>();
        long total = 0;
        for (var entry : configured.entrySet()) {
            Object value = entry.getValue();
            if (!UnitDef.UNITS.containsKey(entry.getKey()) || !(value instanceof Number number)
                    || !Double.isFinite(number.doubleValue()) || number.doubleValue() != number.longValue()
                    || number.longValue() < 0 || number.longValue() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("迎战兵力必须为有效兵种的非负整数");
            }
            int count = ((Number) value).intValue();
            result.put(entry.getKey(), count);
            total += count;
        }
        if (total > cap) throw new IllegalArgumentException("迎战兵力超过出城迎战上限 " + cap + "（当前选择 " + total + "）");
        return result;
    }

    /**
     * 按被攻击城市的实时驻军和容量生成迎战编队；未设置时自动选兵。
     * 驻军不足先按兵种截断，再按比例缩减超额编队，避免换城或加成失效后越过上限。
     * 工事不占迎战名额；固定兵种顺序用于分配整数余数，保证每次选兵结果一致。
     */
    @Transactional(readOnly = true)
    public Map<String, Integer> selectSortieArmy(Long playerId, Map<String, Integer> available) {
        Player player = players.findById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        Map<String, Integer> configured = player.getSortieArmy() == null
                ? available : JsonUtil.parseIntMap(player.getSortieArmy());
        Map<String, Integer> selected = new LinkedHashMap<>();
        UnitDef.UNITS.keySet().stream().sorted().forEach(unit -> {
            int count = Math.min(Math.max(0, configured.getOrDefault(unit, 0)),
                    Math.max(0, available.getOrDefault(unit, 0)));
            if (count > 0) selected.put(unit, count);
        });
        long total = selected.values().stream().mapToLong(Integer::longValue).sum();
        int cap = armyService.sortieCap(playerId);
        if (total <= cap) return selected;
        Map<String, Integer> limited = new LinkedHashMap<>();
        selected.forEach((unit, count) -> limited.put(unit, (int) ((long) count * cap / total)));
        int remaining = cap - limited.values().stream().mapToInt(Integer::intValue).sum();
        for (String unit : selected.keySet()) {
            if (remaining == 0) break;
            if (limited.get(unit) < selected.get(unit)) {
                limited.put(unit, limited.get(unit) + 1);
                remaining--;
            }
        }
        limited.values().removeIf(count -> count == 0);
        return limited;
    }

    /** 仅将有存活兵力的兵种转换为默认指令；工事与 NPC 不使用玩家账号配置。 */
    @Transactional(readOnly = true)
    public Map<String, BattleService.UnitOrder> orders(Long playerId, boolean defending, Map<String, Integer> army) {
        if (playerId == null) return Map.of();
        Player player = players.findById(playerId).orElse(null);
        if (player == null) return Map.of();
        Map<String, BattleService.UnitOrder> orders = new LinkedHashMap<>();
        Map<String, String> configured = effective(defending ? player.getDefendingBattleActions() : player.getOutgoingBattleActions());
        for (Map.Entry<String, Integer> entry : army.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0 || !UnitDef.UNITS.containsKey(entry.getKey())) continue;
            orders.put(entry.getKey(), new BattleService.UnitOrder(
                    BattleService.CommandAction.valueOf(configured.get(entry.getKey()))));
        }
        return orders;
    }

    private Map<String, String> validate(Map<String, String> actions) {
        if (actions == null || !actions.keySet().equals(UnitDef.UNITS.keySet())) {
            throw new IllegalArgumentException("请为所有兵种选择默认战术");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String id : UnitDef.UNITS.keySet()) {
            String action = actions.get(id);
            try {
                result.put(id, BattleService.CommandAction.valueOf(action).name());
            } catch (IllegalArgumentException | NullPointerException ex) {
                throw new IllegalArgumentException("兵种 " + id + " 的默认战术无效");
            }
        }
        return result;
    }

    private Map<String, String> effective(String json) {
        Map<String, Object> saved = JsonUtil.parseObjMap(json);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, UnitDef> entry : UnitDef.UNITS.entrySet()) {
            String action = String.valueOf(saved.get(entry.getKey()));
            if (!"ADVANCE".equals(action) && !"RETREAT".equals(action) && !"HOLD".equals(action)) {
                action = Boolean.FALSE.equals(entry.getValue().autoAdvance()) ? "HOLD" : "ADVANCE";
            }
            result.put(entry.getKey(), action);
        }
        return result;
    }
}
