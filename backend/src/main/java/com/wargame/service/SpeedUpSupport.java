package com.wargame.service;

import com.wargame.model.constants.GameData;
import com.wargame.model.constants.ItemDef;
import com.wargame.repository.PlayerItemRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 加速符调度工具 - 抽离 "扣道具 / 校验 / 退还 / 清理 0 数量记录" 逻辑。
 * <p>
 * 由 {@link BuildService#useSpeedUp} 和 {@link ArmyService#useSpeedUp} 共用，
 * 避免建筑施工与军队生产两侧的加速流程在道具层出现差异。
 */
@Component
public class SpeedUpSupport {

    private final PlayerItemRepository playerItemRepository;

    public SpeedUpSupport(PlayerItemRepository playerItemRepository) {
        this.playerItemRepository = playerItemRepository;
    }

    /**
     * 校验加速符是否合法并原子扣减 count 个。
     *
     * @return 道具定义；扣减成功；失败时 result 中写入错误信息
     */
    public ItemDef consume(Long playerId, String itemId, int count, long now, Map<String, Object> result) {
        ItemDef item = GameData.ITEMS.get(itemId);
        if (item == null || item.speedUpSeconds() <= 0) {
            result.put("success", false);
            result.put("message", "无效的加速符类型: " + itemId);
            return null;
        }
        if (count <= 0) count = 1;
        int consumed = playerItemRepository.tryConsume(playerId, itemId, count, now);
        if (consumed <= 0) {
            result.put("success", false);
            result.put("message", item.name() + " 数量不足");
            return null;
        }
        return item;
    }

    /** 校验加速符是否合法并原子扣减 1 个 */
    public ItemDef consumeOne(Long playerId, String itemId, long now, Map<String, Object> result) {
        return consume(playerId, itemId, 1, now, result);
    }

    /** 加速未命中目标时退回 count 个道具 */
    public void refund(Long playerId, String itemId, int count, long now) {
        if (count <= 0) count = 1;
        playerItemRepository.tryConsume(playerId, itemId, -count, now);
    }

    /** 加速未命中目标时退回 1 个道具 */
    public void refund(Long playerId, String itemId, long now) {
        refund(playerId, itemId, 1, now);
    }

    /** 当 count 归 0 时清理 PlayerItem 记录，避免脏数据 */
    public void cleanupZeroCount(Long playerId, String itemId) {
        playerItemRepository.findByPlayerIdAndItemKey(playerId, itemId).ifPresent(pi -> {
            if (pi.getCount() != null && pi.getCount() <= 0) {
                playerItemRepository.delete(pi);
            }
        });
    }
}
