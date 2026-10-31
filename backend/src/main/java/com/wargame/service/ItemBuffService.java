package com.wargame.service;

import com.wargame.model.constants.ItemDef;
import com.wargame.model.constants.MobilizationRules;
import com.wargame.model.entity.CityState;
import com.wargame.repository.CityStateRepository;
import com.wargame.repository.PlayerCityRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.util.GameClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 只读汇总账号全部城市的持续道具效果，不创建状态、不结算城市经济、不改变道具时长。 */
@Service
@RequiredArgsConstructor
public class ItemBuffService {
    private final PlayerRepository players;
    private final PlayerCityRepository cities;
    private final CityStateRepository states;

    public record City(Long id, String name, int x, int y, boolean main) {}
    public record Effect(String id, String kind, String name, String icon, String scope,
                         Long cityId, int percent, long until, String status) {}
    public record Snapshot(Long playerId, long serverTime, List<City> cities, List<Effect> effects) {}

    /** 账号来自鉴权；仅把仍属于该账号的城市状态投影出去，不泄露失去城市的残留状态。 */
    @Transactional(readOnly = true)
    public Snapshot snapshot(Long playerId) {
        var player = players.findById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        long now = GameClock.now();
        Map<Integer, CityState> bySlot = new HashMap<>();
        states.findAllByPlayerId(playerId).forEach(state -> bySlot.put(state.getCitySlot(), state));
        List<City> owned = new ArrayList<>();
        List<Effect> effects = new ArrayList<>();
        for (var city : cities.findByOwnerIdAndCitySlotIsNotNullOrderByCitySlotAsc(playerId)) {
            owned.add(new City(city.getId(), city.getName(), city.getX(), city.getY(), city.getCitySlot() == 0));
            var state = bySlot.get(city.getCitySlot());
            if (state == null) continue;
            if (state.getShieldUntil() != null && state.getShieldUntil() > now) {
                effects.add(new Effect("shield:" + city.getId(), "shield", "免战护盾",
                        ItemDef.ITEMS.get("shield").icon(), "city", city.getId(), 0, state.getShieldUntil(), "active"));
            }
            if (state.getMarchBoostUntil() != null && state.getMarchBoostUntil() > now) {
                int percent = (int) Math.round((state.marchSpeedMultiplier(now) - 1) * 100);
                String item = ItemDef.MARCH_BONUS_PERCENT.entrySet().stream()
                        .filter(entry -> entry.getValue() == percent).map(Map.Entry::getKey).findFirst().orElse("marchOrd");
                effects.add(new Effect("march:" + city.getId(), "march", ItemDef.ITEMS.get(item).name(),
                        ItemDef.ITEMS.get(item).icon(), "city", city.getId(), percent, state.getMarchBoostUntil(), "active"));
            }
        }
        int highest = MobilizationRules.bonus(player, now);
        // 所有未到期档位都返回；客户端才能在高档到期后立即展示仍有效的低档。
        MobilizationRules.expiries(player).forEach((item, until) -> {
            if (until <= now) return;
            int percent = ItemDef.MOBILIZATION_BONUS_PERCENT.get(item);
            effects.add(new Effect("mobilization:" + item, "mobilization", ItemDef.ITEMS.get(item).name(),
                    ItemDef.ITEMS.get(item).icon(), "account", null, percent, until,
                    percent == highest ? "active" : "covered"));
        });
        return new Snapshot(playerId, now, List.copyOf(owned), List.copyOf(effects));
    }
}
