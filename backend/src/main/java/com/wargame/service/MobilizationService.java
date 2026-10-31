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
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private ArmyService army;

    public MobilizationService(PlayerRepository players, PlayerItemRepository items, MobilizationUseRepository uses) {
        this.players = players;
        this.items = items;
        this.uses = uses;
    }

    /** 生效档位由服务器按游戏时钟选择，客户端只显示剩余时间。 */
    public Map<String, Object> view(Long playerId) {
        Player player = players.findById(playerId).orElseThrow();
        long now = GameClock.now();
        var active = MobilizationRules.expiries(player).entrySet().stream().filter(e -> e.getValue() > now)
                .map(e -> Map.<String, Object>of("id", e.getKey(), "name", ItemDef.ITEMS.get(e.getKey()).name(),
                        "percent", ItemDef.MOBILIZATION_BONUS_PERCENT.get(e.getKey()), "until", e.getValue())).toList();
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("active", active);
        result.put("current", active.isEmpty() ? null : active.get(active.size() - 1));
        result.put("percent", MobilizationRules.bonus(player, now));
        result.put("key", active.isEmpty() ? "default" : "mobilization:" + player.getMobilizationCycle());
        return result;
    }

    private long extendedUntil(Player player, String item, int count, long now) {
        return Math.addExact(Math.max(now, MobilizationRules.expiries(player).get(item)), Math.multiplyExact(MobilizationRules.DURATION_MS, count));
    }

    public Map<String, Object> preview(Long playerId, String itemId, int count) {
        if (!ItemDef.MOBILIZATION_BONUS_PERCENT.containsKey(itemId) || count < 1 || count > 10000)
            throw new IllegalArgumentException("动员令参数无效");
        Player player = players.findById(playerId).orElseThrow();
        long now = GameClock.now();
        return Map.of("until", extendedUntil(player, itemId, count, now), "percent", ItemDef.MOBILIZATION_BONUS_PERCENT.get(itemId),
                "currentPercent", MobilizationRules.bonus(player, now), "armyCap", MobilizationRules.apply(army.ordinaryArmyCap(playerId), Math.max(MobilizationRules.bonus(player, now), ItemDef.MOBILIZATION_BONUS_PERCENT.get(itemId))), "addedHours", count * 24L);
    }

    /** 库存、续时和动员周期在账号锁下原子提交；重试复用 requestId，不重复扣卡。 */
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
        long until = extendedUntil(player, itemId, count, now);
        boolean newCycle = MobilizationRules.bonus(player, now) == 0;
        if (items.tryConsume(playerId, itemId, count, now) == 0) throw new IllegalArgumentException("动员令数量不足");
        // 原子扣库存会清空持久化上下文，重读仍被本事务锁定的账号后再写增益。
        player = players.findById(playerId).orElseThrow();
        if (newCycle) {
            player.setMobilizationCycle(player.getMobilizationCycle() + 1);
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
