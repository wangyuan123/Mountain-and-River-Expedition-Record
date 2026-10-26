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
@EntityListeners(com.wargame.service.RadarChangeListener.class)
@Table(name = "player_cities")
public class PlayerCity extends VersionedEntity implements CityEconomy {

    @Column(name = "radar_enabled", nullable = false)
    private boolean radarEnabled;

    @Column(name = "radar_revision", nullable = false)
    private long radarRevision;

    /** 城市易主后必须由新城主重新启用联防，不能继承原城主授权。 */
    public void setOwnerId(Long value) {
        if (!java.util.Objects.equals(ownerId, value)) {
            radarEnabled = false;
            radarRevision++;
        }
        ownerId = value;
    }

    @Column(name = "legacy_naval", nullable = false)
    private boolean legacyNaval = false;

    @Column(name = "garrison_food_at")
    private Long garrisonFoodAt;

    /** 新老城市均需城主主动开启；关闭不移除已驻扎编队。 */
    @Column(name = "accept_garrison", nullable = false)
    private boolean acceptGarrison = false;
    @Column(name = "garrison_food_remainder", nullable = false)
    private double garrisonFoodRemainder;
    @Column(name = "garrison_food_unmet", nullable = false)
    private double garrisonFoodUnmet;
    /** 缺粮告警每次补给中断只发一次；恢复后允许下一次告警。 */
    @Column(name = "garrison_supply_warned", nullable = false)
    private boolean garrisonSupplyWarned;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "world_id", nullable = false)
    private Long worldId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "city_name_renamed_at", nullable = false)
    private long cityNameRenamedAt = 0L;

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
    /** Non-null while a diplomatic city transfer is pending or in progress. */
    @Column(name = "surrender_offer_id") private Long surrenderOfferId;
    @Column(name = "surrender_recipient_id") private Long surrenderRecipientId;
    @Column(name = "surrender_frozen_at") private Long surrenderFrozenAt;
    @Column(name = "surrender_transfer_deadline_at") private Long surrenderTransferDeadlineAt;

    public boolean isSurrenderFrozen(long now) {
        return surrenderOfferId != null && surrenderFrozenAt != null && surrenderFrozenAt <= now;
    }
    public String getCityName() { return name; }
    public void setCityName(String value) { name = value; }
    public Integer getCityPosX() { return x; }
    public void setCityPosX(Integer value) { x = value; }
    public Integer getCityPosY() { return y; }
    public void setCityPosY(Integer value) { y = value; }
}
