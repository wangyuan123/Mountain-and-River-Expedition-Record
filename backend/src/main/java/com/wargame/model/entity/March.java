package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@lombok.EqualsAndHashCode(callSuper = false)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "marches")
public class March extends CityOwnedEntity {

    @Column(name = "route_data", columnDefinition = "longtext")
    private String routeData;

    /** 出发时冻结行动装备数值；缺字段的旧行军按无装备处理。 */
    @Column(name = "unit_equipment_snapshot", columnDefinition = "longtext")
    private String unitEquipmentSnapshot;

    @Column(name = "route_mode", length = 30)
    private String routeMode;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "target_kind", length = 50)
    private String targetKind;

    @Column(name = "target_id", length = 100)
    private String targetId;

    @Column(name = "target_name", length = 100)
    private String targetName;

    @Column(name = "target_x")
    private Integer targetX;

    @Column(name = "target_y")
    private Integer targetY;

    @Column(name = "from_x")
    private Integer fromX;

    @Column(name = "from_y")
    private Integer fromY;

    /** 单程直线距离，以地图格边长为单位；保留小数用于行军计时和油耗。 */
    @Column(name = "distance")
    private Double distance;

    @Column(name = "action", length = 50)
    private String action;

    /** 军团驻防保留出发玩家及城市归属；驻扎期间不再重复执行抵达结算。 */
    @Column(name = "stationed", nullable = false)
    private Boolean stationed = false;

    /** 支援发出时的城主，防止城市易主后物资送给新的所有者。 */
    @Column(name = "support_owner_id")
    private Long supportOwnerId;

    /** PvE 攻击的出征选择；旧行军为 null 时按目标类型沿用原有行为。 */
    @Column(name = "battle_mode", length = 10)
    private String battleMode;

    /** 征服野地胜利后的去向：withdraw 自动撤军，occupy 留守目标野地。 */
    @Column(name = "conquer_mode", length = 10)
    private String conquerMode = "withdraw";

    @Column(name = "army", columnDefinition = "text")
    private String army;

    @Column(name = "commander_id")
    private Long commanderId;

    @Column(name = "carry_res", columnDefinition = "text")
    private String carryRes;

    @Column(name = "start_at")
    private Long startAt;

    @Column(name = "arrive_at")
    private Long arriveAt;

    /** 未使用本队加速时的完整单程耗时，返程与野地驻军沿用该值。 */
    @Column(name = "base_duration_ms")
    private Long baseDurationMs;

    /** 去程与返程各限一次，刷新或重复请求不能再次扣令加速。 */
    @Column(name = "outbound_accelerated", nullable = false)
    private Boolean outboundAccelerated = false;

    @Column(name = "return_accelerated", nullable = false)
    private Boolean returnAccelerated = false;

    @Column(name = "returning")
    private Boolean returning;

    @Column(name = "gathering")
    private Boolean gathering;

    @Column(name = "gather_mode", nullable = false, length = 10)
    private String gatherMode = "auto";

    @Column(name = "gather_start_at")
    private Long gatherStartAt;

    @Column(name = "gather_stopped", nullable = false)
    private Boolean gatherStopped = false;

    @Column(name = "gather_end_at")
    private Long gatherEndAt;

    @Column(name = "gather_amount")
    private Integer gatherAmount;

    @Column(name = "gather_res", columnDefinition = "text")
    private String gatherRes;

    /** 撤回前的目标名（仅在 returning=true 时有值，便于 UI 展示"撤自 X"） */
    @Column(name = "origin_name", length = 100)
    private String originName;

    @Column(name = "origin_x")
    private Integer originX;

    @Column(name = "origin_y")
    private Integer originY;

    /** 已到达且等待玩家逐回合指挥的战斗会话。 */
    @Column(name = "battle_id")
    private Long battleId;

    /** 进攻战斗是否胜利，用于战况展示。返程光带统一为蓝色。 */
    @Column(name = "battle_won")
    private Boolean battleWon;
}
