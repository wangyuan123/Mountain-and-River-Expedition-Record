package com.wargame.service;

import com.wargame.repository.BattleSessionRepository;
import org.springframework.stereotype.Service;

/** 城市实际进入守城战后禁止所有出征；会话终局删除即自动解除，不锁定同账号其他城市。 */
@Service
public class CityBattleLockService {
    public static final String MESSAGE = "当前城市正在战斗，暂时无法出征，请等待战斗结束";
    private final CityScope scope;
    private final BattleSessionRepository battles;

    public CityBattleLockService(CityScope scope, BattleSessionRepository battles) {
        this.scope = scope;
        this.battles = battles;
    }

    /** 状态展示只读取实际城市 ID 对应的守城会话，不依赖雷达是否能看到来袭。 */
    public boolean locked(Long playerId) {
        return scope.selected(playerId).map(city -> battles.existsByTargetKindAndTargetId("player", city.getId().toString()))
                .orElse(false);
    }

    /** 调用前必须取得城主账号写锁，与建战场及终局结算串行；当前读避免旧事务快照漏掉刚创建的战斗。 */
    public void requireDispatchAllowed(Long playerId) {
        scope.selected(playerId).ifPresent(city -> {
            if (!battles.findActivePlayerCityBattles(city.getId().toString()).isEmpty()) {
                throw new com.wargame.security.GameAccessException("CITY_IN_BATTLE", MESSAGE, 409);
            }
        });
    }
}
