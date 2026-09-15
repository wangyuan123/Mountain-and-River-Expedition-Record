package com.wargame.service;

import com.wargame.model.constants.MilitaryRankDef;
import com.wargame.model.entity.Player;
import com.wargame.model.entity.PlayerItem;
import com.wargame.repository.PlayerItemRepository;
import com.wargame.repository.PlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RankService {

    private final PlayerRepository playerRepository;
    private final PlayerItemRepository playerItemRepository;
    private final WebSocketPushService pushService;

    public RankService(PlayerRepository playerRepository,
                       PlayerItemRepository playerItemRepository,
                       WebSocketPushService pushService) {
        this.playerRepository = playerRepository;
        this.playerItemRepository = playerItemRepository;
        this.pushService = pushService;
    }

    public Map<String, Object> getRankInfo(Long playerId) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在: " + playerId));

        int curTier = player.getMilitaryRank() != null ? player.getMilitaryRank() : 1;
        MilitaryRankDef.RankInfo curRank = MilitaryRankDef.getRank(curTier);
        int curPrestige = player.getPrestige() != null ? player.getPrestige() : 0;

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("cityCap", MilitaryRankDef.getCityCap(curTier));
        res.put("nextCityCap", MilitaryRankDef.getCityCap(Math.min(curTier + 1, MilitaryRankDef.MAX_RANK_TIER)));
        var nextCityRank = MilitaryRankDef.nextCityRank(curTier);
        res.put("nextCityRankName", nextCityRank == null ? null : nextCityRank.name());
        res.put("nextExpansionCap", nextCityRank == null ? MilitaryRankDef.getCityCap(curTier) : MilitaryRankDef.getCityCap(nextCityRank.tier()));
        res.put("tier", curTier);
        res.put("name", curRank.name());
        res.put("baseCap", curRank.baseCap());
        res.put("prestige", curPrestige);
        res.put("isMax", curTier >= MilitaryRankDef.MAX_RANK_TIER);

        if (curTier >= MilitaryRankDef.MAX_RANK_TIER) {
            res.put("nextTier", null);
            res.put("nextName", null);
            res.put("nextBaseCap", null);
            res.put("reqPrestige", null);
            res.put("reqGems", List.of());
            res.put("canPromote", false);
            res.put("prestigeEnough", true);
            res.put("gemsEnough", true);
            return res;
        }

        int nextTier = curTier + 1;
        MilitaryRankDef.RankInfo nextRank = MilitaryRankDef.getRank(nextTier);
        res.put("nextTier", nextTier);
        res.put("nextName", nextRank.name());
        res.put("nextBaseCap", nextRank.baseCap());
        res.put("reqPrestige", nextRank.prestige());

        boolean prestigeEnough = curPrestige >= nextRank.prestige();
        boolean allGemsEnough = true;

        List<Map<String, Object>> gemList = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : nextRank.reqGems().entrySet()) {
            String gemKey = entry.getKey();
            int reqCount = entry.getValue();
            int owned = playerItemRepository.findByPlayerIdAndItemKey(playerId, gemKey)
                    .map(PlayerItem::getCount).orElse(0);

            boolean enough = owned >= reqCount;
            if (!enough) allGemsEnough = false;

            MilitaryRankDef.GemDef def = MilitaryRankDef.GEMS.get(gemKey);
            Map<String, Object> gemInfo = new LinkedHashMap<>();
            gemInfo.put("key", gemKey);
            gemInfo.put("name", def != null ? def.name() : gemKey);
            gemInfo.put("icon", def != null ? def.icon() : "💎");
            gemInfo.put("required", reqCount);
            gemInfo.put("owned", owned);
            gemInfo.put("enough", enough);
            gemList.add(gemInfo);
        }

        res.put("reqGems", gemList);
        res.put("prestigeEnough", prestigeEnough);
        res.put("gemsEnough", allGemsEnough);
        res.put("canPromote", prestigeEnough && allGemsEnough);

        return res;
    }

    @Transactional
    public Map<String, Object> promoteRank(Long playerId) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在: " + playerId));

        int curTier = player.getMilitaryRank() != null ? player.getMilitaryRank() : 1;
        if (curTier >= MilitaryRankDef.MAX_RANK_TIER) {
            throw new IllegalStateException("您已晋升至最高统帅军衔【" + MilitaryRankDef.getRankName(curTier) + "】！");
        }

        int nextTier = curTier + 1;
        MilitaryRankDef.RankInfo nextRank = MilitaryRankDef.getRank(nextTier);
        int curPrestige = player.getPrestige() != null ? player.getPrestige() : 0;

        if (curPrestige < nextRank.prestige()) {
            throw new IllegalArgumentException("晋升需要声望 " + nextRank.prestige() + "，当前声望不足！");
        }

        // 校验并扣除珠宝道具
        List<PlayerItem> toUpdate = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : nextRank.reqGems().entrySet()) {
            String gemKey = entry.getKey();
            int reqCount = entry.getValue();
            PlayerItem pi = playerItemRepository.findByPlayerIdAndItemKey(playerId, gemKey)
                    .orElse(null);
            int owned = pi != null ? pi.getCount() : 0;
            if (owned < reqCount) {
                MilitaryRankDef.GemDef def = MilitaryRankDef.GEMS.get(gemKey);
                String gemName = def != null ? def.name() : gemKey;
                throw new IllegalArgumentException("缺少晋升珠宝【" + gemName + "】(拥有 " + owned + "/" + reqCount + ")");
            }
            pi.setCount(owned - reqCount);
            pi.setUpdatedAt(System.currentTimeMillis());
            toUpdate.add(pi);
        }

        playerItemRepository.saveAll(toUpdate);

        // 晋升
        player.setMilitaryRank(nextTier);
        playerRepository.save(player);

        pushService.pushToPlayer(playerId, "rank_promoted", Map.of("tier", nextTier, "rankName", nextRank.name()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("tier", nextTier);
        result.put("rankName", nextRank.name());
        result.put("cityCap", MilitaryRankDef.getCityCap(nextTier));
        result.put("message", "恭喜您晋升为【" + nextRank.name() + "】！基础出兵上限增加，最多可拥有 " + MilitaryRankDef.getCityCap(nextTier) + " 座城市。");

        return result;
    }
}
