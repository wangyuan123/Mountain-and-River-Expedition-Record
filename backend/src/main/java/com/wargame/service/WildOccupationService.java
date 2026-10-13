package com.wargame.service;

import com.wargame.model.entity.Building;
import com.wargame.model.entity.WildTile;
import com.wargame.repository.BuildingRepository;
import com.wargame.repository.WildTileRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 每城独立管理野地：基础5块，加本城市政厅已完成等级，最高15块。 */
@Service
@RequiredArgsConstructor
public class WildOccupationService {
    public static final int BASE_CAP = 5;
    private static final int MAX_COMMAND_LEVEL = 10;
    private final BuildingRepository buildings;
    private final WildTileRepository wilds;
    private final AccountService accounts;
    @PersistenceContext private EntityManager em;

    public record Limit(int citySlot, int commandLevel, int cap, long ownedCount) {
        public boolean full() { return ownedCount >= cap; }
        public Map<String, Object> state(List<Map<String, Object>> owned) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("citySlot", citySlot); state.put("baseCap", BASE_CAP);
            state.put("commandLevel", commandLevel); state.put("cap", cap);
            state.put("ownedCount", ownedCount); state.put("owned", owned);
            return state;
        }
        public String fullMessage() {
            return "本城野地占领已达上限（" + ownedCount + "/" + cap + "），请升级市政厅或放弃本城领地";
        }
    }

    public record Claim(boolean claimed, String reason) {}

    /** 查询指定城市的名额；分城等级与其他城市领地都不参与本城计算。 */
    @Transactional(readOnly = true)
    public Limit limit(Long playerId, int citySlot) {
        return limit(citySlot, buildings.findByPlayerIdAndCitySlotAndType(playerId, citySlot, "command"),
                wilds.countByOccupiedByAndOccupiedCitySlotAndOccupiedTrue(playerId, citySlot));
    }

    /** 当前城市野地页需要的轻量领地列表；驻军和采集详情仍由地图接口提供。 */
    @Transactional(readOnly = true)
    public Map<String, Object> state(Long playerId, int citySlot) {
        Limit limit = limit(playerId, citySlot);
        List<Map<String, Object>> owned = new ArrayList<>();
        wilds.findByOccupiedByAndOccupiedCitySlotAndOccupiedTrue(playerId, citySlot).forEach(tile -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", tile.getId()); item.put("type", tile.getType());
            item.put("level", tile.getLevel()); item.put("x", tile.getX()); item.put("y", tile.getY());
            item.put("totalRes", Objects.requireNonNullElse(tile.getTotalRes(), 0));
            item.put("mined", Objects.requireNonNullElse(tile.getMined(), 0));
            owned.add(item);
        });
        return limit.state(owned);
    }

    /** 出征前拦截已满/超额的城市；在途征服不预扣名额，战后再次校验。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireRoom(Long playerId, int citySlot) {
        Limit limit = lockedLimit(playerId, citySlot);
        if (limit.full()) throw new IllegalArgumentException(limit.fullMessage());
    }

    /**
     * 在战斗结算事务中占领并登记出发城市；同账号的不同战斗共用账号锁防止超额。
     * @return 实际占领结果，名额不足或目标已被占领时返回原因，供战报和返城使用
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claim(Long playerId, int citySlot, WildTile tile) {
        Limit limit = lockedLimit(playerId, citySlot);
        // refresh自身取得写锁并替换旧版本；先对缓存实体执行锁查询会触发版本冲突。
        em.refresh(tile, LockModeType.PESSIMISTIC_WRITE);
        if (Boolean.TRUE.equals(tile.getOccupied())) return new Claim(false, "目标已被占领，未取得野地归属");
        if (limit.full()) return new Claim(false, limit.fullMessage() + "，本次胜利未占领野地");
        tile.setOccupied(true);
        tile.setOccupiedBy(playerId);
        tile.setOccupiedCitySlot(citySlot);
        tile.setScouted(true);
        return new Claim(true, null);
    }

    private Limit lockedLimit(Long playerId, int citySlot) {
        accounts.lockPlayer(playerId);
        // 先锁ID再刷新实体，避免等待账号锁前读取的旧版本触发锁升级冲突。
        List<Building> halls = buildings.lockCityHallIds(playerId, citySlot).stream()
                .map(id -> em.find(Building.class, id)).toList();
        halls.forEach(hall -> em.refresh(hall, LockModeType.PESSIMISTIC_WRITE));
        return limit(citySlot, halls, wilds.lockOwnedIdsByCity(playerId, citySlot).size());
    }

    private Limit limit(int citySlot, List<Building> halls, long ownedCount) {
        int level = halls.stream().mapToInt(b -> Objects.requireNonNullElse(b.getLevel(), 0)).max().orElse(0);
        level = Math.max(0, Math.min(MAX_COMMAND_LEVEL, level));
        return new Limit(citySlot, level, BASE_CAP + level, ownedCount);
    }
}
