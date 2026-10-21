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
@Table(name = "incoming_marches")
public class IncomingMarch extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_player_id", nullable = false)
    private Long targetPlayerId;

    @Column(name = "from_name", length = 100)
    private String fromName;

    @Column(name = "from_x")
    private Integer fromX;

    @Column(name = "from_y")
    private Integer fromY;

    @Column(name = "army", columnDefinition = "text")
    private String army;

    @Column(name = "arrive_at")
    private Long arriveAt;

    /** 旧来袭记录也独立保存本次去程的伏击消费状态。 */
    @Column(nullable = false)
    private Boolean ambushUsed = false;

    @Column(name = "action", length = 50)
    private String action;
}
