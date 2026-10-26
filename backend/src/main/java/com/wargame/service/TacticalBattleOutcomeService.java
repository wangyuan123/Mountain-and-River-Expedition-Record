package com.wargame.service;

import com.wargame.model.entity.TacticalBattleOutcome;
import com.wargame.repository.TacticalBattleOutcomeRepository;
import com.wargame.util.JsonUtil;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

@Service
public class TacticalBattleOutcomeService {
    private final TacticalBattleOutcomeRepository outcomes;
    public TacticalBattleOutcomeService(TacticalBattleOutcomeRepository outcomes) { this.outcomes = outcomes; }

    /** 与战斗结算同事务保存，保留真实时间30天，和普通战报详情期限一致。 */
    public void save(Long marchId, Long attackerId, Long defenderId, Map<String, Object> attack, Map<String, Object> defense) {
        TacticalBattleOutcome row = new TacticalBattleOutcome();
        row.setMarchId(marchId); row.setAttackerId(attackerId); row.setDefenderId(defenderId);
        row.setAttackerView(JsonUtil.toJson(attack));
        row.setDefenderView(defense == null ? null : JsonUtil.toJson(defense));
        row.setExpiresAt(System.currentTimeMillis() + 30L * 86400000);
        outcomes.save(row);
    }

    /** 按原参战身份读取终局；调用方使用读已提交，确保可见刚提交的后台结算。 */
    @Transactional(readOnly = true)
    public Map<String, Object> find(Long playerId, Long marchId) {
        TacticalBattleOutcome row = outcomes.findById(marchId).orElse(null);
        if (row == null || row.getExpiresAt() <= System.currentTimeMillis()) return null;
        if (playerId.equals(row.getAttackerId())) return JsonUtil.parseObjMap(row.getAttackerView());
        if (playerId.equals(row.getDefenderId()) && row.getDefenderView() != null) return JsonUtil.parseObjMap(row.getDefenderView());
        throw new IllegalArgumentException("无权查看该战斗");
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    @Transactional
    public void expire() { outcomes.deleteByExpiresAtLessThanEqual(System.currentTimeMillis()); }
}
