package com.wargame.model.dto;

import java.util.Map;

/**
 * 游戏 API 请求 DTO 集合 - 对应各控制器的请求体。
 */
public class GameDtos {

    // ===== 游戏设置 =====

    public record TaxRequest(Integer tax) {}

    public record ResetRequest(Boolean confirm) {}

    public record CityNameRequest(String cityName) {}
    public record AvatarRequest(String avatar) {}

    public record GuildSettingsRequest(String name, String icon) {}

    public record GuildRoleRequest(String role) {}

    // ===== 建筑 =====

    public record BuildRequest(String building, Integer slot) {}

    public record FreeBuildSpeedUpRequest(Long queueId) {}

    public record SpeedUpRequest(String itemId, Long queueId, Integer count, String building, Integer slot) {
        public SpeedUpRequest(String itemId, Long queueId, Integer count) {
            this(itemId, queueId, count, null, null);
        }
        public SpeedUpRequest(String itemId, Long queueId) {
            this(itemId, queueId, 1, null, null);
        }
    }

    // ===== 军队 =====

    public record ArmyRequest(String unit, Integer count) {}

    // ===== 科技 =====

    public record TechRequest(String tech) {}

    // ===== 军官 =====

    public record OfficerIdxRequest(Integer officerIdx) {}

    public record OfficerIdRequest(Long officerId) {}

    public record OfficerEquipmentRequest(Long officerId, String itemId) {}

    public record OfficerAppointRequest(Long officerId, String role) {}

    public record OfficerAbandonSkillRequest(Long officerId, Integer skillIdx) {}
    
    public record OfficerLevelUpRequest(Long officerId, Boolean all) {}

    public record OfficerExpBookRequest(Long officerId, String itemId, Integer count) {}

    public record OfficerAssignAttrRequest(Long officerId, String attr, Integer points) {}

    // ===== 城防 =====

    public record FortRequest(String fort, Integer count) {}

    // ===== 世界 =====

    public record MoveRequest(String direction) {}

    public record DeclareWarRequest(Long targetCityId) {}

    public record CancelMarchRequest(Long marchId) {}

    /** 战术战斗中每个己方兵种本回合的机动与可选集火目标。 */
    public record BattleUnitOrderRequest(String action, String focusTarget) {}

    /**
     * round 是客户端读取战场时的回合号，用于拒绝已经被自动结算推进的过期指令。
     */
    public record BattleCommandRequest(Map<String, BattleUnitOrderRequest> orders, Integer round) {
        public BattleCommandRequest(Map<String, BattleUnitOrderRequest> orders) {
            this(orders, null);
        }
    }

    public record ReseedRequest(Integer count, Integer centerX, Integer centerY, Integer radius) {}

    // ===== 野地 =====

    public record WildTileRequest(Long wildTileId) {}

    public record WildDispatchRequest(
            Long wildTileId,
            Map<String, Integer> army,
            Long commanderId,
            Map<String, Integer> carryRes
    ) {}

    // ===== 商城 =====

    public record ShopRechargeRequest(String pkgId, int rmb, int diamond, String channel) {}

    public record ShopBuyRequest(String itemId) {}

    // ===== 仓库 =====

    public record DepotUseRequest(String itemId, Long officerId, String newName) {}

    // ===== 内政民心 =====

    public record AppeaseRequest(String type) {}
}
