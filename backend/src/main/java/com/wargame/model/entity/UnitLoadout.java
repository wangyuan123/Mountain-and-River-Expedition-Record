package com.wargame.model.entity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
/** 账号统一配装，不带城市与兵数。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "unit_loadouts", uniqueConstraints = @UniqueConstraint(columnNames = {"player_id", "unit_type"}))
public class UnitLoadout extends VersionedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "player_id", nullable = false) private Long playerId;
    @Column(name = "unit_type", nullable = false, length = 30) private String unitType;
    @Column(name = "slots", nullable = false, columnDefinition = "text") private String slots;
}
