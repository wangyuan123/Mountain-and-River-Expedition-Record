package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;

/** 每对账号独立保存个人战争；兼容玩家字段只作展示快照，不再承担关系存储。 */
@Data
@Entity
@Table(name = "personal_wars", uniqueConstraints = @UniqueConstraint(columnNames = {"player_low_id", "player_high_id"}))
public class PersonalWar extends VersionedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "player_low_id", nullable = false) private Long playerLowId;
    @Column(name = "player_high_id", nullable = false) private Long playerHighId;
    @Column(name = "initiator_id", nullable = false) private Long initiatorId;
    @Column(name = "city_slot", nullable = false) private int citySlot;
    @Column(name = "target_city_id", nullable = false) private Long targetCityId;
    @Column(name = "declared_at", nullable = false) private long declaredAt;
    @Column(name = "war_at", nullable = false) private long warAt;
    @Column(name = "war_end_at", nullable = false) private long warEndAt;
    @Column(nullable = false) private boolean accelerated;
    @Column(name = "order_effect", columnDefinition = "text") private String orderEffect;
}
