package com.wargame.service;

import com.wargame.repository.BattleSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps timed tactical battles advancing even when an unrelated player economy tick is delayed. */
@Component
@ConditionalOnProperty(name = "game.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class TacticalBattleScheduler {
    private static final Logger log = LoggerFactory.getLogger(TacticalBattleScheduler.class);
    private static final long COMMAND_SUBMISSION_GRACE_MS = 2_000L;

    private final BattleSessionRepository battleSessions;
    private final MarchService marches;
    private final int batchSize;

    public TacticalBattleScheduler(BattleSessionRepository battleSessions, MarchService marches,
                                   @Value("${game.tactical-battle-batch-size:100}") int batchSize) {
        this.battleSessions = battleSessions;
        this.marches = marches;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${game.tactical-battle-interval:1000}")
    public void resolveTimedOutRounds() {
        long now = System.currentTimeMillis();
        // 页面截止时提交已选命令；后台延迟兜底，避免先结算而丢弃玩家的本回合指令。
        for (Long battleSessionId : battleSessions.findDueRoundIds(now - COMMAND_SUBMISSION_GRACE_MS, PageRequest.of(0, batchSize))) {
            try {
                marches.processTimedOutTacticalBattle(battleSessionId, now);
            } catch (Exception e) {
                log.error("Timed tactical battle resolution failed for session {}", battleSessionId, e);
            }
        }
    }
}
