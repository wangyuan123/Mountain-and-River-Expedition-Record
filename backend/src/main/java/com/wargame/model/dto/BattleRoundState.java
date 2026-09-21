package com.wargame.model.dto;

import java.util.Map;

/** 单个战术回合后的可持久化战场快照。 */
public record BattleRoundState(
        int round,
        boolean finished,
        boolean attackerWin,
        Map<String, Integer> attackerArmy,
        Map<String, Integer> defenderArmy,
        Map<String, Integer> attackerPositions,
        Map<String, Integer> defenderPositions,
        String log
) {}
