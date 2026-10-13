package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 独立演习持久化记录；不得用于真实世界结算。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "pvp_practice_reports")
public class PvpPracticeReport extends VersionedEntity {
    @Id
    @Column(name = "id", length = 36)
    private String id;
    @Column(name = "session_id", length = 36)
    private String sessionId;
    @Column(name = "player_id")
    private Long playerId;
    @Column(name = "created_at", nullable = false)
    private long createdAt;
    @Column(name = "result", length = 20)
    private String result;
    @Column(name = "data", columnDefinition = "longtext")
    private String data;
}
