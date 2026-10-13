package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 独立演习持久化记录；不得用于真实世界结算。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "pvp_round_results")
public class PvpRoundResult extends VersionedEntity {
    @Id
    @Column(name = "id", length = 36)
    private String id;
    @Column(name = "session_id", length = 36)
    private String sessionId;
    @Column(name = "round_no", nullable = false)
    private int roundNo;
    @Column(name = "seed", nullable = false)
    private long seed;
    @Column(name = "data", columnDefinition = "longtext")
    private String data;
}
