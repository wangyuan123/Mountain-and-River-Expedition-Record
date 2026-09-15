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

    @Column(name = "route_mode", length = 30)
    private String routeMode;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "target_kind", length = 50)
    private String targetKind;

    @Column(name = "target_idx")
    private Integer targetIdx;

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

    @Column(name = "distance")
    private Integer distance;

    @Column(name = "action", length = 50)
    private String action;

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

    @Column(name = "returning")
    private Boolean returning;

    @Column(name = "gathering")
    private Boolean gathering;

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
}
