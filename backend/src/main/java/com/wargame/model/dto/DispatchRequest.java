package com.wargame.model.dto;

import java.util.Map;

/**
 * 出征请求 DTO - 对应 JS world.js launchDispatch 中的派遣参数。
 */
public record DispatchRequest(
        String targetKind,
        Long targetId,
        String action,
        Map<String, Integer> army,
        Long commanderId,
        Map<String, Integer> carryRes,
        String gatherMode,
        String battleMode,
        String conquerMode,
        String unitEquipmentVersion,
        Boolean abandonNewbieProtection
) {
    public DispatchRequest(String targetKind, Long targetId, String action, Map<String, Integer> army,
                           Long commanderId, Map<String, Integer> carryRes, String gatherMode, String battleMode,
                           String conquerMode, String unitEquipmentVersion) {
        this(targetKind, targetId, action, army, commanderId, carryRes, gatherMode, battleMode, conquerMode, unitEquipmentVersion, false);
    }

    public DispatchRequest(String targetKind, Long targetId, String action, Map<String, Integer> army,
                           Long commanderId, Map<String, Integer> carryRes, String gatherMode, String battleMode, String conquerMode) {
        this(targetKind, targetId, action, army, commanderId, carryRes, gatherMode, battleMode, conquerMode, null);
    }

    public DispatchRequest(String targetKind, Long targetId, String action, Map<String, Integer> army,
                           Long commanderId, Map<String, Integer> carryRes) {
        this(targetKind, targetId, action, army, commanderId, carryRes, null, null, null);
    }

    public DispatchRequest(String targetKind, Long targetId, String action, Map<String, Integer> army,
                           Long commanderId, Map<String, Integer> carryRes, String gatherMode) {
        this(targetKind, targetId, action, army, commanderId, carryRes, gatherMode, null, null);
    }

    public DispatchRequest(String targetKind, Long targetId, String action, Map<String, Integer> army,
                           Long commanderId, Map<String, Integer> carryRes, String gatherMode,
                           String battleMode) {
        this(targetKind, targetId, action, army, commanderId, carryRes, gatherMode, battleMode, null);
    }
}
