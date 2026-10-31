package com.wargame.model.dto;

import java.util.Map;

/**
 * 出征请求 DTO - 对应 JS world.js launchDispatch 中的派遣参数。
 * 派兵采集已取消；gatherMode、gatherAfterAction 仅保留用于明确拒绝旧请求，采集需进驻后手动启动。
 * mobilizationOrderItem 仅保留用于拒绝旧页面请求；动员令统一在仓库启用账号限时增益。
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
        String actionSnapshotVersion,
        Boolean abandonNewbieProtection,
        Integer targetX,
        Integer targetY,
        String gatherAfterAction,
        String mobilizationOrderItem
) {
    public DispatchRequest(String targetKind, Long targetId, String action, Map<String, Integer> army,
                           Long commanderId, Map<String, Integer> carryRes, String gatherMode, String battleMode,
                           String conquerMode, String actionSnapshotVersion, Boolean abandonNewbieProtection,
                           Integer targetX, Integer targetY, String gatherAfterAction) {
        this(targetKind, targetId, action, army, commanderId, carryRes, gatherMode, battleMode, conquerMode,
                actionSnapshotVersion, abandonNewbieProtection, targetX, targetY, gatherAfterAction, null);
    }

    public DispatchRequest(String targetKind, Long targetId, String action, Map<String, Integer> army,
                           Long commanderId, Map<String, Integer> carryRes, String gatherMode, String battleMode,
                           String conquerMode, String actionSnapshotVersion, Boolean abandonNewbieProtection,
                           Integer targetX, Integer targetY) {
        this(targetKind, targetId, action, army, commanderId, carryRes, gatherMode, battleMode, conquerMode,
                actionSnapshotVersion, abandonNewbieProtection, targetX, targetY, null, null);
    }

    public DispatchRequest(String targetKind, Long targetId, String action, Map<String, Integer> army,
                           Long commanderId, Map<String, Integer> carryRes, String gatherMode, String battleMode,
                           String conquerMode, String actionSnapshotVersion, Boolean abandonNewbieProtection) {
        this(targetKind, targetId, action, army, commanderId, carryRes, gatherMode, battleMode, conquerMode, actionSnapshotVersion, abandonNewbieProtection, null, null, null, null);
    }

    public DispatchRequest(String targetKind, Long targetId, String action, Map<String, Integer> army,
                           Long commanderId, Map<String, Integer> carryRes, String gatherMode, String battleMode,
                           String conquerMode, String actionSnapshotVersion) {
        this(targetKind, targetId, action, army, commanderId, carryRes, gatherMode, battleMode, conquerMode, actionSnapshotVersion, null, null, null, null, null);
    }

    public DispatchRequest(String targetKind, Long targetId, String action, Map<String, Integer> army,
                           Long commanderId, Map<String, Integer> carryRes, String gatherMode, String battleMode, String conquerMode) {
        this(targetKind, targetId, action, army, commanderId, carryRes, gatherMode, battleMode, conquerMode, null, null, null, null, null, null);
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
        this(targetKind, targetId, action, army, commanderId, carryRes, gatherMode, battleMode, null, null, null, null, null, null);
    }
}
