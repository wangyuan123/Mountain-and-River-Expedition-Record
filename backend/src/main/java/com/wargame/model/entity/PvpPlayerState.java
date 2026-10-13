package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 独立演习持久化记录；不得用于真实世界结算。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "pvp_player_states")
public class PvpPlayerState extends VersionedEntity {
    @Id
    @Column(name = "player_id")
    private Long playerId;
    @Column(name = "active_ticket_id", length = 36)
    private String activeTicketId;
    @Column(name = "active_session_id", length = 36)
    private String activeSessionId;
    @Column(name = "last_session_id", length = 36)
    private String lastSessionId;
    @Column(name = "preset", columnDefinition = "longtext")
    private String preset;
    @Column(name = "preset_version", nullable = false)
    private long presetVersion;
    @Column(name = "rating", nullable = false)
    private int rating = 1000;
    @Column(name = "games", nullable = false)
    private int games;
    @Column(name = "wins", nullable = false)
    private int wins;
    @Column(name = "losses", nullable = false)
    private int losses;
    @Column(name = "draws", nullable = false)
    private int draws;
    @Column(name = "recent_opponents", columnDefinition = "text")
    private String recentOpponents;
    @Column(name = "confirmation_window", nullable = false)
    private long confirmationWindow;
    @Column(name = "confirmation_exits", nullable = false)
    private int confirmationExits;
    @Column(name = "cooldown_until", nullable = false)
    private long cooldownUntil;
    @Column(name = "sequence_no", nullable = false)
    private long sequence;
}
