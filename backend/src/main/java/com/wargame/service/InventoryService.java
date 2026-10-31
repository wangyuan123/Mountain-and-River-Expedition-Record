package com.wargame.service;

import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.util.GameClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 道具奖励与装备返还共用账号锁及容量校验；异常交由外层事务整体回滚。 */
@Service
@RequiredArgsConstructor
public class InventoryService {
    private final PlayerRepository players;
    private final PlayerItemRepository items;

    /** 在扣除礼包、领取资格的同一事务内入库，不截断奖励，也不保存溢出数量。 */
    @Transactional
    public void add(Long playerId, String key, int count) {
        if (count < 0) throw new IllegalArgumentException("入库数量不能为负数");
        if (count == 0) return;
        players.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        PlayerItem item = items.findForPurchase(playerId, key).orElseGet(PlayerItem::new);
        long total = (long) (item.getCount() == null ? 0 : item.getCount()) + count;
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("道具库存已达上限，请先使用部分库存");
        item.setPlayerId(playerId);
        item.setItemKey(key);
        item.setCount((int) total);
        item.setUpdatedAt(GameClock.now());
        items.save(item);
    }
}
