package com.wargame.service;

import com.wargame.model.constants.UnitDef;
import com.wargame.model.entity.Player;
import com.wargame.repository.PlayerRepository;
import com.wargame.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** 玩家账号级兵种默认战术；离线结算也使用服务端保存的配置。 */
@Service
public class BattleActionPreferences {
    private final PlayerRepository players;

    public BattleActionPreferences(PlayerRepository players) {
        this.players = players;
    }

    /** 返回所有兵种的有效默认行为，未自定义时沿用兵种配置。 */
    @Transactional(readOnly = true)
    public Map<String, Map<String, String>> get(Long playerId) {
        Player player = players.findById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        return Map.of("outgoing", effective(player.getOutgoingBattleActions()),
                "defending", effective(player.getDefendingBattleActions()));
    }

    /** 保存攻守两套完整配置；拒绝未知兵种或非法命令，防止离线回合使用无效指令。 */
    @Transactional
    public Map<String, Map<String, String>> save(Long playerId, Map<String, Map<String, String>> preferences) {
        if (preferences == null || !preferences.keySet().equals(java.util.Set.of("outgoing", "defending"))) {
            throw new IllegalArgumentException("请同时提交出城和守城的默认战术");
        }
        Map<String, String> outgoing = validate(preferences.get("outgoing"));
        Map<String, String> defending = validate(preferences.get("defending"));
        Player player = players.findById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        player.setOutgoingBattleActions(JsonUtil.toJson(outgoing));
        player.setDefendingBattleActions(JsonUtil.toJson(defending));
        players.save(player);
        return get(playerId);
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
