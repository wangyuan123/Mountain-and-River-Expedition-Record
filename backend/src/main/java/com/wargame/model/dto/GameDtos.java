package com.wargame.model.dto;

import java.util.Map;
import java.util.List;

/**
 * 游戏 API 请求 DTO 集合 - 对应各控制器的请求体。
 */
public class GameDtos {

    // ===== 游戏设置 =====

    public record TaxRequest(Integer tax) {}

    public record ResetRequest(Boolean confirm) {}

    public record CityNameRequest(String cityName) {}
    public record DisplayNameRequest(String name) {}
    public record AvatarRequest(String avatar) {}
    public record HomeModuleOrderRequest(List<String> order) {}

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

    public record OfficerUpgradeSkillRequest(Long officerId, Integer skillIdx, String itemId) {}
    
    public record OfficerLevelUpRequest(Long officerId, Boolean all) {}

    public record OfficerExpBookRequest(Long officerId, String itemId, Integer count) {}

    public record OfficerAssignAttrRequest(Long officerId, String attr, Integer points) {}

    public record OfficerRenameRequest(Long officerId, String name) {}

    // ===== 城防 =====

    public record FortRequest(String fort, Integer count) {}

    // ===== 世界 =====

    public record MoveRequest(String direction) {}

    /** 退出新手保护必须随本次宣战显式确认，旧客户端默认不退出。 */
    public record DeclareWarRequest(Long targetCityId, Boolean abandonNewbieProtection) {
        public DeclareWarRequest(Long targetCityId) { this(targetCityId, false); }
    }

    public record CancelMarchRequest(Long marchId) {}

    /** 阶段与版本取自确认弹窗打开时的行军快照，防止旧请求消耗新阶段的道具。 */
    public record SpeedUpMarchRequest(Long marchId, Boolean returning, Long version, String confirmationToken) {}

    /** 只对确认时仍在途的敌军行军使用一次计谋。 */
    public record MarchStratagemRequest(Long marchId, String itemKey, Long expectedArriveAt, String confirmationToken) {}

    public record OrderPreviewRequest(Long marchId, String itemKey) {}

    public record WarOrderRequest(Long targetCityId, String confirmationToken) {}

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

    public record WildTileRequest(Long wildTileId, Boolean abandonNewbieProtection) {
        public WildTileRequest(Long wildTileId) { this(wildTileId, false); }
    }

    public record WildDispatchRequest(
            Long wildTileId,
            Map<String, Integer> army,
            Long commanderId,
            Map<String, Integer> carryRes,
            String gatherMode,
            String conquerMode,
            Boolean abandonNewbieProtection
    ) {
        public WildDispatchRequest(Long wildTileId, Map<String, Integer> army, Long commanderId,
                                   Map<String, Integer> carryRes, String gatherMode, String conquerMode) {
            this(wildTileId, army, commanderId, carryRes, gatherMode, conquerMode, false);
        }
        public WildDispatchRequest(Long wildTileId, Map<String, Integer> army, Long commanderId, Map<String, Integer> carryRes) {
            this(wildTileId, army, commanderId, carryRes, null, null);
        }

        public WildDispatchRequest(Long wildTileId, Map<String, Integer> army, Long commanderId,
                                   Map<String, Integer> carryRes, String gatherMode) {
            this(wildTileId, army, commanderId, carryRes, gatherMode, null);
        }
    }

    // ===== 商城 =====

    public record ShopRechargeRequest(String pkgId, int rmb, int diamond, String channel) {}

    public record ShopBuyRequest(String itemId, Integer count) {
        public ShopBuyRequest(String itemId) {
            this(itemId, 1);
        }

        @Override
        public Integer count() {
            return (count == null || count < 1) ? 1 : count;
        }
    }

    // ===== 仓库 =====

    /** skillId 仅供通用技能书自选学习；三参数构造保持既有调用兼容。 */
    public record DepotUseRequest(String itemId, Long officerId, String newName, String skillId) {
        public DepotUseRequest(String itemId, Long officerId, String newName) {
            this(itemId, officerId, newName, null);
        }
    }

    // ===== 内政民心 =====

    public record AppeaseRequest(String type) {}

    // ===== 交易所 / 市场 =====
    public record MarketCreateOrderRequest(String resourceType, Integer amount, Integer pricePerUnit) {}
    public record MarketBuyOrderRequest(Long orderId) {}
    public record MarketCancelOrderRequest(Long orderId) {}
    public record MarketExchangeRequest(String fromRes, String toRes, Integer amount) {}
}
