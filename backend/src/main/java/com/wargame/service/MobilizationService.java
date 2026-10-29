package com.wargame.service;

import com.wargame.model.constants.ItemDef;
import com.wargame.model.constants.MobilizationRules;
import com.wargame.model.entity.MobilizationUse;
import com.wargame.model.entity.Player;
import com.wargame.repository.MobilizationUseRepository;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.repository.PlayerRepository;
import com.wargame.util.GameClock;
import com.wargame.util.JsonUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

@Service
public class MobilizationService {
    private final PlayerRepository players;
    private final PlayerItemRepository items;
    private final MobilizationUseRepository uses;
    @PersistenceContext private EntityManager em;

    public MobilizationService(PlayerRepository players, PlayerItemRepository items, MobilizationUseRepository uses) {
        this.players = players;
        this.items = items;
        this.uses = uses;
    }

    /** 库存、续时和首次复制编队在账号锁下原子提交；重试复用 requestId，不重复扣卡。 */
    @Transactional
    public void use(Long playerId, String itemId, Number amount, String requestId) {
        if (amount != null && (!Double.isFinite(amount.doubleValue()) || amount.doubleValue() != amount.longValue()
                || amount.longValue() < 1 || amount.longValue() > 10000))
            throw new IllegalArgumentException("使用数量必须为1至10000的整数");
        int count = amount == null ? 1 : amount.intValue();
        if (!ItemDef.MOBILIZATION_BONUS_PERCENT.containsKey(itemId)) throw new IllegalArgumentException("动员令类型无效");
        if (count < 1 || count > 10000) throw new IllegalArgumentException("使用数量必须为1至10000的整数");
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{8,64}")) throw new IllegalArgumentException("请刷新仓库后确认使用动员令");
        Player player = players.lockById(playerId).orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        em.refresh(player);
        String receiptId = playerId + ":" + requestId;
        var prior = uses.findById(receiptId);
        if (prior.isPresent()) {
            if (!itemId.equals(prior.get().getItemId()) || count != prior.get().getAmount())
                throw new IllegalArgumentException("重复请求的道具或数量不一致，请重新打开弹窗");
            return;
        }
        long now = GameClock.now();
        Map<String, Long> expiries = MobilizationRules.expiries(player);
        long until = Math.addExact(Math.max(now, expiries.get(itemId)), Math.multiplyExact(MobilizationRules.DURATION_MS, count));
        boolean newCycle = MobilizationRules.bonus(player, now) == 0;
        if (items.tryConsume(playerId, itemId, count, now) == 0) throw new IllegalArgumentException("动员令数量不足");
        // 原子扣库存会清空持久化上下文，重读仍被本事务锁定的账号后再写增益。
        player = players.findById(playerId).orElseThrow();
        if (newCycle) {
            player.setMobilizationCycle(player.getMobilizationCycle() + 1);
            player.setMobilizationSortieArmy(player.getSortieArmy());
        }
        expiries.put(itemId, until);
        player.setMobilizationExpiries(JsonUtil.toJson(expiries));
        players.save(player);
        MobilizationUse receipt = new MobilizationUse();
        receipt.setId(receiptId);
        receipt.setPlayerId(playerId);
        receipt.setItemId(itemId);
        receipt.setAmount(count);
        receipt.setUsedAt(now);
        uses.save(receipt);
    }
}
