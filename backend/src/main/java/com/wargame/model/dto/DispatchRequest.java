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
        Map<String, Integer> carryRes
) {}
