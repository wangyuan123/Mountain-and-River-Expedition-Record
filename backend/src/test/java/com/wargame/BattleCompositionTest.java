package com.wargame;

import com.wargame.model.dto.BattleResult;
import com.wargame.service.BattleService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证更换编成带来的实际收益，包含攻坚时间限制与护航的经济取舍。 */
class BattleCompositionTest {
    private BattleResult battle(Map<String, Integer> attack, Map<String, Integer> defense,
                                Map<String, Integer> forts, int seed, boolean developed) {
        Map<String, Integer> tech = developed ? Map.of("attack_tech", 5, "defense_tech", 5,
                "cmd_hp", 5, "weapon_range", 5, "arm_engine", 5, "air_engine", 5) : Map.of();
        return new BattleService(seed).startWorldDispatch(attack, defense, forts, tech, tech,
                Map.of(), Map.of(), developed ? 50 : 0, developed ? 50 : 0, 0, 0,
                "plunder", Map.of(), 0, true);
    }

    @Test
    void dedicatedSiegeClearsBunkersWithinRoundLimitAtComparableBudget() {
        // 碉堡射程提至1050后，喀秋莎火箭（range=2000）作为射程压制工事的超视距攻坚武器，
        // 需验证在30回合上限内拥有足够的攻坚火力彻底清空碉堡群。
        for (boolean developed : List.of(false, true)) {
            for (String id : List.of("rocket")) {
                int count = 140;
                int wins = 0;
                for (int seed = 0; seed < 32; seed++) {
                    BattleResult result = battle(Map.of(id, count), Map.of(), Map.of("bunker", 700), seed, developed);
                    if (result.getSurvivorDefender().isEmpty()) wins++;
                }
                assertTrue(wins >= 24, id + " developed=" + developed + " wins=" + wins);
            }
        }
    }

    @Test
    void replacingSomeBombersWithEscortImprovesOutcomeAgainstFighters() {
        // 加权造价：纯轰炸机50,400，混编49,800，敌战斗机48,000；改善不能靠额外预算。
        for (boolean developed : List.of(false, true)) {
            int pureWins = 0, escortedWins = 0;
            for (int seed = 0; seed < 32; seed++) {
                if (battle(Map.of("bomber", 60), Map.of("fighter", 100), Map.of(), seed, developed)
                        .getSurvivorDefender().isEmpty()) pureWins++;
                if (battle(Map.of("armored", 120, "bomber", 25), Map.of("fighter", 100), Map.of(), seed, developed)
                        .getSurvivorDefender().isEmpty()) escortedWins++;
            }
            assertTrue(escortedWins >= 24 && escortedWins > pureWins,
                    "developed=" + developed + " pure=" + pureWins + " escorted=" + escortedWins);
        }
    }
}
