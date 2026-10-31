package com.wargame.model.dto.pvp;

import com.wargame.model.dto.CommandBonus;
import com.wargame.service.BattleService;
import java.util.Map;

/** 入队时冻结的参战情报，不含坐标、仓库、未参战军官。 */
public record PvpSnapshot(String snapshotId, String playerName, int citySlot, String battlefield,
        Map<String, Integer> army, Map<String, Integer> tech, Map<String, Integer> skills,
        Long commanderId, String commanderName, int military, int defense, int logistics, int knowledge,
        Object officerEquipment, CommandBonus chief, Map<String, BattleService.UnitOrder> defaults,
        Map<String, Double> baseValues, Map<String, Map<String, Double>> effectiveStats,
        String rulesVersion, String powerModelVersion, long power, double skillFactor) {
    /** 展示冻结时的行动火力，不读取玩家当前科技。 */
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public double firepowerMultiplier() { return com.wargame.model.constants.TechnologyRules.multiplier(tech, "fire_density"); }
    public BattleService.TechCtx context() { return new BattleService.TechCtx(tech, skills, military, defense, chief); }
    public PvpSnapshot withPower(long p, double factor, String model) {
        return new PvpSnapshot(snapshotId, playerName, citySlot, battlefield, army, tech, skills, commanderId,
                commanderName, military, defense, logistics, knowledge, officerEquipment, chief, defaults,
                baseValues, effectiveStats, rulesVersion, model, p, factor);
    }
}
