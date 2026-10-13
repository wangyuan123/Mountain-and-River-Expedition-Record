package com.wargame.model.dto.pvp;
import com.wargame.service.BattleService.UnitOrder;
import java.util.Map;
public record PvpPreset(int citySlot, String battlefield, Map<String, Integer> army,
        Long commanderId, Map<String, UnitOrder> defaults) {}
