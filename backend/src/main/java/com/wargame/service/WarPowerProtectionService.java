package com.wargame.service;

import com.wargame.repository.PlayerPowerSnapshotRepository;
import com.wargame.util.GameClock;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WarPowerProtectionService {
    public static final int MAX_RATIO = 10;
    private final PlayerPowerService power;
    private final PlayerPowerSnapshotRepository snapshots;

    @Value("${game.war-power.enabled:true}")
    private boolean enabled;

    public record Eligibility(boolean allowed, String reason, PlayerPowerService.Scores own,
                              PlayerPowerService.Scores target) {
        public Map<String, Object> toMap() {
            return Map.of("allowed", allowed, "reason", reason, "maxRatio", MAX_RATIO,
                    "ownFormationPower", own.formationPower(), "ownOverallPower", own.overallPower(),
                    "targetFormationPower", target.formationPower(), "targetOverallPower", target.overallPower());
        }
    }

    /** 双向账号级门槛，任一指标超限均禁止城市战争；恰好十倍允许。 */
    public Eligibility evaluate(Long ownId, Long targetId) {
        return evaluateMany(ownId, List.of(targetId)).get(targetId);
    }

    /** 地图同一账号的多个城市复用一次计算，不逐城重复读取双方资产。 */
    public Map<Long, Eligibility> evaluateMany(Long ownId, Collection<Long> targetIds) {
        if (targetIds.isEmpty()) return Map.of();
        if (!enabled) {
            Map<Long, Eligibility> result = new HashMap<>();
            PlayerPowerService.Scores defaultScores = new PlayerPowerService.Scores(0, 0);
            for (Long targetId : targetIds) {
                result.put(targetId, new Eligibility(true, "", defaultScores, defaultScores));
            }
            return result;
        }
        Set<Long> players = new LinkedHashSet<>(targetIds);
        players.add(ownId);
        Map<Long, PlayerPowerService.Scores> ratings = new HashMap<>();
        for (Long playerId : players) {
            PlayerPowerService.Scores current = power.calculate(playerId).get(playerId);
            if (current != null) ratings.put(playerId, current);
        }
        for (Object[] peak : snapshots.peaks(new ArrayList<>(players), GameClock.now() - PlayerPowerHistoryService.WINDOW_MS)) {
            Long playerId = ((Number) peak[0]).longValue();
            PlayerPowerService.Scores current = ratings.get(playerId);
            if (current != null) ratings.put(playerId, new PlayerPowerService.Scores(
                    Math.max(current.formationPower(), ((Number) peak[1]).longValue()),
                    Math.max(current.overallPower(), ((Number) peak[2]).longValue())));
        }
        Map<Long, Eligibility> result = new HashMap<>();
        for (Long targetId : targetIds) result.put(targetId, compare(ratings.get(ownId), ratings.get(targetId)));
        return result;
    }

    private Eligibility compare(PlayerPowerService.Scores own, PlayerPowerService.Scores target) {
        String reason = "";
        if (own == null || target == null) reason = "实力数据暂不可用，请刷新后重试";
        else if (!withinLimit(own.formationPower(), target.formationPower())) reason = "双方编队战力差距超过10倍，无法宣战或攻打城市";
        else if (!withinLimit(own.overallPower(), target.overallPower())) reason = "双方综合实力差距超过10倍，无法宣战或攻打城市";
        return new Eligibility(reason.isEmpty(), reason,
                own == null ? new PlayerPowerService.Scores(0, 0) : own,
                target == null ? new PlayerPowerService.Scores(0, 0) : target);
    }

    /** 零对零同档，零对正数禁止；整除比较避免 long 乘法溢出。 */
    public static boolean withinLimit(long first, long second) {
        if (first < 0 || second < 0) return false;
        long low = Math.min(first, second), high = Math.max(first, second);
        return high / MAX_RATIO < low || high / MAX_RATIO == low && high % MAX_RATIO == 0;
    }
}
