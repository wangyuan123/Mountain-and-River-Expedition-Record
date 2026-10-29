package com.wargame.service;

import com.wargame.model.constants.*;
import com.wargame.model.entity.*;
import com.wargame.repository.*;
import com.wargame.util.GameClock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RankGiftService {
    private final AccountService accounts;
    private final PlayerRepository players;
    private final PlayerItemRepository items;
    private final ResourcesRepository resources;
    private final RankGiftRewardRepository rewards;
    private final EntityManager em;

    /** 首次进入或晋升前完成启程/上线补发；仅使用数据库固定快照，不追溯历史阶数。 */
    @Transactional
    public void enroll(Long playerId) {
        Player player = accounts.lockPlayer(playerId);
        if (!player.isGameInitialized() || player.isRankGiftsEnrolled()) return;
        int baseline = player.getRankGiftBaselineTier();
        issue(playerId, baseline > 0 ? baseline : 1, baseline > 0 ? "legacy" : "initial");
        player.setRankGiftsEnrolled(true);
        players.save(player);
    }

    /** 只能由成功授衔的事务调用；账号锁覆盖回执及入仓，调用失败随授衔整体回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> awardPromotion(Long playerId, int tier) {
        Player player = accounts.lockPlayer(playerId);
        if (!player.isGameInitialized() || tier < 2 || tier != player.getMilitaryRank())
            throw new IllegalStateException("军衔礼包发放资格无效");
        return view(issue(playerId, tier, "promotion"));
    }

    /** 调用方必须持账号锁；数据库唯一约束覆盖不同来源的重复发放。 */
    private RankGiftReward issue(Long playerId, int tier, String source) {
        if (tier < 1 || tier > MilitaryRankDef.MAX_RANK_TIER) throw new IllegalStateException("军衔礼包档位无效");
        var existing = rewards.findForUpdate(playerId, tier);
        if (existing.isPresent()) return existing.get();
        long now = GameClock.now();
        RankGiftReward receipt = new RankGiftReward();
        receipt.setPlayerId(playerId); receipt.setRankTier(tier); receipt.setItemKey(RankGiftDef.key(tier));
        receipt.setDiamonds(RankGiftDef.diamonds(tier)); receipt.setSource(source); receipt.setIssuedAt(now);
        rewards.save(receipt);
        PlayerItem stock = items.findForPurchase(playerId, receipt.getItemKey())
                .orElseGet(() -> new PlayerItem(null, playerId, receipt.getItemKey(), 0, now));
        // 专属礼包没有其他来源；异常库存拒绝发放，避免回执与物品数量失配。
        if (stock.getCount() != 0) throw new IllegalStateException("军衔礼包库存异常");
        stock.setCount(1); stock.setUpdatedAt(now); items.save(stock);
        return receipt;
    }

    /** 开包只兑现回执承诺金额，主城账本保存全账号钻石；所有变更在同一事务中提交。 */
    @Transactional
    public Map<String, Object> open(Long playerId, String itemKey) {
        Player player = accounts.lockPlayer(playerId);
        Integer tier = RankGiftDef.tier(itemKey);
        if (tier == null) return error("无效的军衔钻石礼包");
        var found = rewards.findForUpdate(playerId, tier);
        if (found.isEmpty() || found.get().getOpenedAt() != null) return error("军衔礼包未发放或已开启");
        RankGiftReward receipt = found.get();
        if (!receipt.getItemKey().equals(itemKey) || tier > player.getMilitaryRank()) return error("军衔礼包资格无效");
        PlayerItem stock = items.findForPurchase(playerId, itemKey).orElse(null);
        if (stock == null || stock.getCount() != 1) return error("军衔礼包库存不足或异常");
        Resources wallet = resources.findCityForTreatment(playerId, 0).orElseThrow(() -> new IllegalStateException("账号钻石账本不存在"));
        // 锁后刷新，防止请求鉴权或其他服务提前读取的实体覆盖最新共享余额。
        em.refresh(wallet, LockModeType.PESSIMISTIC_WRITE);
        long balance = Objects.requireNonNullElse(wallet.getDiamond(), 0);
        if (receipt.getDiamonds() <= 0 || balance < 0 || balance + receipt.getDiamonds() > Integer.MAX_VALUE)
            return error("钻石余额无法容纳礼包奖励，请先消费部分钻石后重试");
        long now = GameClock.now();
        stock.setCount(0); stock.setUpdatedAt(now); items.save(stock);
        wallet.setDiamond((int) balance + receipt.getDiamonds()); resources.save(wallet);
        receipt.setOpenedAt(now); rewards.save(receipt);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true); result.put("diamonds", receipt.getDiamonds());
        result.put("message", "已开启【" + RankGiftDef.name(tier) + "】，钻石 +" + receipt.getDiamonds() + "，已计入账号共享余额");
        if (tier < MilitaryRankDef.MAX_RANK_TIER && player.getMilitaryRank() < tier + 1) {
            var next = MilitaryRankDef.getRank(tier + 1);
            if (items.findForPurchase(playerId, next.certificateKey()).map(PlayerItem::getCount).orElse(0) <= 0) {
                result.put("nextCertificateTier", next.tier()); result.put("nextCertificateName", next.certificateName());
            }
        }
        return result;
    }

    /** 资格页奖励预告使用实时配置；已发礼包展示与兑现均使用不可变金额快照。 */
    public Map<String, Object> preview(int tier) {
        return metadata(tier, RankGiftDef.diamonds(tier));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> state(Long playerId) {
        Map<String, Integer> stocks = new HashMap<>();
        items.findByPlayerId(playerId).forEach(item -> stocks.put(item.getItemKey(), item.getCount()));
        List<Map<String, Object>> entries = rewards.findByPlayerIdOrderByRankTierAsc(playerId).stream().map(receipt -> {
            Map<String, Object> entry = view(receipt);
            entry.put("count", receipt.getOpenedAt() == null ? stocks.getOrDefault(receipt.getItemKey(), 0) : 0);
            return entry;
        }).toList();
        return Map.of("rewards", entries, "unopenedCount", entries.stream().mapToInt(e -> (int) e.get("count")).sum());
    }

    private Map<String, Object> view(RankGiftReward receipt) {
        Map<String, Object> result = metadata(receipt.getRankTier(), receipt.getDiamonds());
        result.put("source", receipt.getSource()); result.put("issuedAt", receipt.getIssuedAt());
        result.put("openedAt", receipt.getOpenedAt());
        return result;
    }
    private Map<String, Object> metadata(int tier, int diamonds) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("itemKey", RankGiftDef.key(tier)); result.put("tier", tier); result.put("name", RankGiftDef.name(tier));
        result.put("diamonds", diamonds); result.put("icon", RankGiftDef.ICON); result.put("cat", ItemDef.CAT_RESOURCE);
        result.put("desc", RankGiftDef.description(tier, diamonds));
        if (tier < MilitaryRankDef.MAX_RANK_TIER) {
            result.put("nextCertificateTier", tier + 1);
            result.put("nextCertificateName", MilitaryRankDef.getRank(tier + 1).certificateName());
        }
        return result;
    }
    private Map<String, Object> error(String message) {
        return new LinkedHashMap<>(Map.of("success", false, "message", message));
    }
}
