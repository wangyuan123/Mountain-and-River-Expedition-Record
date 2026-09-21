package com.wargame;

import com.wargame.model.dto.BattleRoundState;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 验证战术指挥接口只结算调用方要求的一回合。 */
class TacticalBattleRoundTest {

    @Test
    void resolvesExactlyOneRoundWithoutAutoCompletingBattle() {
        BattleService service = new BattleService(0);
        BattleRoundState state = service.resolveWorldRound(
                Map.of("infantry", 20), Map.of("infantry", 20),
                Map.of("infantry", 0), Map.of("infantry", 1000), 1000,
                Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, 0, 0, 0, 0, 1,
                Map.of("infantry", new BattleService.UnitOrder(BattleService.CommandAction.HOLD)), Map.of());

        assertEquals(1, state.round());
        assertFalse(state.finished());
        assertEquals(20, state.attackerArmy().get("infantry"));
        assertEquals(20, state.defenderArmy().get("infantry"));
    }
}
