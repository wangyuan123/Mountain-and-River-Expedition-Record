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
@Table(name = "resources")
public class Resources extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "food")
    private Integer food;

    @Column(name = "steel")
    private Integer steel;

    @Column(name = "oil")
    private Integer oil;

    @Column(name = "rare")
    private Integer rare;

    @Column(name = "gold")
    private Integer gold;

    @Column(name = "diamond")
    private Integer diamond;
}
