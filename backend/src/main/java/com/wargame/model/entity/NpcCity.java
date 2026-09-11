package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "npc_cities")
public class NpcCity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "world_id", nullable = false)
    private Long worldId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "level")
    private Integer level;

    @Column(name = "x", nullable = false)
    private Integer x;

    @Column(name = "y", nullable = false)
    private Integer y;

    @Column(name = "army", columnDefinition = "text")
    private String army;

    @Column(name = "forts", columnDefinition = "text")
    private String forts;

    @Column(name = "resources", columnDefinition = "text")
    private String resources;

    @Column(name = "defeated")
    private Boolean defeated;

    @Column(name = "scouted_by", columnDefinition = "text")
    private String scoutedBy;
}
