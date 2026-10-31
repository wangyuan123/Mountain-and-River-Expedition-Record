package com.wargame.service;

import com.wargame.model.constants.UnitDef;
import com.wargame.util.GameClock;
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
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
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

    /** 保留旧客户端数量和动员周期字段用于兼容反序列化；守城全员参战，不再读取或保存这些预设。 */
    public record Preferences(Map<String, String> outgoing, Map<String, String> defending,
                              Map<String, Object> sortieArmy, String formationKey) {
        public Preferences(Map<String, String> outgoing, Map<String, String> defending, Map<String, Object> sortieArmy) {
            this(outgoing, defending, sortieArmy, null);
        }
    }

    /** sortieArmy 恒为 null；sortieCap 兼容旧字段名，仅表示主动出征上限，守城没有人数上限。 */
    public record View(Map<String, String> outgoing, Map<String, String> defending,
                       Map<String, Integer> sortieArmy, int sortieCap, boolean locked, String formationKey) {}

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
        long now = GameClock.now();
        return new View(effective(player.getOutgoingBattleActions()), effective(player.getDefendingBattleActions()),
                null, armyService.armyCapAt(playerId, now), isLocked(playerId), "default");
    }

    /** 保存攻守两套完整配置；拒绝未知兵种或非法命令，防止离线回合使用无效指令。 */
    @Transactional
    public View save(Long playerId, Preferences preferences) {
        Player player = players.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        if (entityManager != null) entityManager.refresh(player);
        if (isLocked(playerId)) throw new IllegalArgumentException("战斗或进攻行军期间无法修改默认战术");
        if (preferences == null) {
            throw new IllegalArgumentException("请同时提交出城和守城的默认战术");
        }
        Map<String, String> outgoing = validate(preferences.outgoing());
        Map<String, String> defending = validate(preferences.defending());
        player.setOutgoingBattleActions(JsonUtil.toJson(outgoing));
        player.setDefendingBattleActions(JsonUtil.toJson(defending));
        players.save(player);
        return get(playerId);
    }

    /**
     * 锁定开战时被攻击城市的全部可战兵力，不读取历史迎战预设，也不使用出征带兵上限。
     * 工事由战斗入口另行加入；伤兵和在途部队不在城市可用兵力中，不会加入本场。
     */
    @Transactional(readOnly = true)
    public Map<String, Integer> selectSortieArmy(Long playerId, Map<String, Integer> available) {
        Map<String, Integer> selected = new LinkedHashMap<>();
        UnitDef.UNITS.keySet().stream().sorted().forEach(unit -> {
            Integer count = available.get(unit);
            if (count != null && count > 0) selected.put(unit, count);
        });
        return selected;
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
            if (entry.getValue() == null || entry.getValue() <= 0 || !UnitDef.UNITS.containsKey(com.wargame.model.constants.BattleUnitIds.base(entry.getKey()))) continue;
            orders.put(entry.getKey(), new BattleService.UnitOrder(
                    BattleService.CommandAction.valueOf(configured.get(com.wargame.model.constants.BattleUnitIds.base(entry.getKey())))));
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
