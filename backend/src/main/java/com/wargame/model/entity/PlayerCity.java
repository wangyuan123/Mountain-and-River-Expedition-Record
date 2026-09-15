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
@Table(name = "player_cities")
public class PlayerCity extends VersionedEntity implements CityEconomy {

    @Column(name = "legacy_naval", nullable = false)
    private boolean legacyNaval = false;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "world_id", nullable = false)
    private Long worldId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "level")
    private Integer level;

    @Column(name = "x", nullable = false)
    private Integer x;

    @Column(name = "y", nullable = false)
    private Integer y;

    /** @deprecated 城市不再单独持久化兵力，兵力统一存于 ArmyUnit。 */
    @Transient
    private String army;

    @Column(name = "forts", columnDefinition = "text")
    private String forts;

    @Column(name = "resources", columnDefinition = "text")
    private String resources;

    @Column(name = "prestige")
    private Integer prestige;

    @Column(name = "war_at")
    private Long warAt;

    @Column(name = "war_end_at")
    private Long warEndAt;

    @Column(name = "scouted_by", columnDefinition = "text")
    private String scoutedBy;

    /** Null identifies old map-only entries; zero is the real player's main city. */
    @Column(name = "city_slot") private Integer citySlot;
    @Column(name = "ready_at", nullable = false) private Long readyAt = 0L;
    @Column(name = "tax", nullable = false) private Integer tax = 30;
    @Column(name = "morale", nullable = false) private Integer morale = 70;
    @Column(name = "resentment", nullable = false) private Integer resentment = 0;
    @Column(name = "last_appease_at", nullable = false) private Long lastAppeaseAt = 0L;
    @Column(name = "civilian_population", nullable = false) private Integer civilianPopulation = 50;
    @Column(name = "population_growth_remainder", nullable = false) private Double populationGrowthRemainder = 0.0;
    @Column(name = "last_tick", nullable = false) private Long lastTick = 0L;
    public String getCityName() { return name; }
    public void setCityName(String value) { name = value; }
    public Integer getCityPosX() { return x; }
    public void setCityPosX(Integer value) { x = value; }
    public Integer getCityPosY() { return y; }
    public void setCityPosY(Integer value) { y = value; }
}
