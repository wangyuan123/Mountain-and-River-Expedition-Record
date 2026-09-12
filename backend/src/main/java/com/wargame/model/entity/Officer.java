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
@Table(name = "officers")
public class Officer extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "star")
    private Integer star;

    @Column(name = "level")
    private Integer level;

    @Column(name = "military")
    private Integer military;

    @Column(name = "logistics")
    private Integer logistics;

    @Column(name = "knowledge")
    private Integer knowledge;

    @Column(name = "loyalty")
    private Integer loyalty;

    @Column(name = "salary")
    private Integer salary;

    @Column(name = "exp")
    private Long exp;

    @Column(name = "role", length = 50)
    private String role;

    @Column(name = "recruit_at")
    private Long recruitAt;

    @Column(name = "reward_at")
    private Long rewardAt;

    @Column(name = "skills", columnDefinition = "text")
    private String skills;

    @Column(name = "bio", columnDefinition = "text")
    private String bio;

    @Column(name = "equipment_count", nullable = false)
    private Integer equipmentCount = 0;

    @Column(name = "attr_points", nullable = false)
    private Integer attrPoints = 0;
}
