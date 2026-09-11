package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "city_state")
public class CityState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "war_target_id")
    private Long warTargetId;

    @Column(name = "war_at")
    private Long warAt;

    @Column(name = "war_end_at")
    private Long warEndAt;

    @Column(name = "shield_until")
    private Long shieldUntil;

    @Column(name = "peace_until")
    private Long peaceUntil;

    @Column(name = "march_boost_until")
    private Long marchBoostUntil;

    @Column(name = "cloak_until")
    private Long cloakUntil;
}
