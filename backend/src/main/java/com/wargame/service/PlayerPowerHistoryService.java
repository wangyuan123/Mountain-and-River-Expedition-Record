package com.wargame.service;

import com.wargame.model.entity.PlayerPowerSnapshot;
import com.wargame.repository.PlayerPowerSnapshotRepository;
import com.wargame.util.GameClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class PlayerPowerHistoryService {
    public static final long WINDOW_MS = 7 * 86_400_000L;
    private final PlayerPowerSnapshotRepository snapshots;

    /** 仅在写事务中留存评级；上涨立即记录，其余状态最多每五分钟采样一次。 */
    public void record(Long playerId, PlayerPowerService.Scores scores) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) return;
        long now = GameClock.now();
        PlayerPowerSnapshot previous = snapshots.findFirstByPlayerIdOrderByObservedAtDescIdDesc(playerId);
        if (previous != null && now - previous.getObservedAt() < 300_000
                && scores.formationPower() <= previous.getFormationPower()
                && scores.overallPower() <= previous.getOverallPower()) return;
        PlayerPowerSnapshot sample = new PlayerPowerSnapshot();
        sample.setPlayerId(playerId);
        sample.setObservedAt(now);
        sample.setFormationPower(scores.formationPower());
        sample.setOverallPower(scores.overallPower());
        snapshots.save(sample);
    }

    @Transactional
    public void prune() {
        snapshots.deleteByObservedAtLessThanEqual(GameClock.now() - WINDOW_MS);
    }
}
