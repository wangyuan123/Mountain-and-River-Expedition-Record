package com.wargame;

import com.wargame.model.constants.BattlePrestige;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证高成本兵种的战损会带来更高的声望影响，且攻守双方变化守恒。 */
class BattlePrestigeTest {

    @Test
    void unitValuesFollowWeightedProductionCosts() {
        assertEquals(1, BattlePrestige.unitValue("infantry"));
        assertEquals(7, BattlePrestige.unitValue("ltank"));
        assertEquals(20, BattlePrestige.unitValue("rocket"));
        assertEquals(12, BattlePrestige.unitValue("htank"));
        assertEquals(11, BattlePrestige.unitValue("destroyer"));
        assertEquals(386, BattlePrestige.unitValue("battleship"));
        assertEquals(772, BattlePrestige.unitValue("carrier"));
        assertEquals(0, BattlePrestige.unitValue("bunker"));
    }

    @Test
    void netChangeUsesUnitValuesAndExcludesFortifications() {
        int attackerChange = BattlePrestige.attackerNetChange(
                Map.of("infantry", 10, "rocket", 1), Map.of(),
                Map.of("infantry", 20, "bunker", 5), Map.of("bunker", 5));

        // 守方损失步兵 20 点；攻方损失步兵 10 点和反坦克火箭车 20 点，净声望为 -10。
        assertEquals(-10, attackerChange);
        assertEquals(-attackerChange, BattlePrestige.attackerNetChange(
                Map.of("infantry", 20, "bunker", 5), Map.of("bunker", 5),
                Map.of("infantry", 10, "rocket", 1), Map.of()));
    }
}
