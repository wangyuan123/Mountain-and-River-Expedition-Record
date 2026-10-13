package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 独立演习持久化记录；不得用于真实世界结算。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "pvp_power_calibrations")
public class PvpPowerCalibration extends VersionedEntity {
    @Id
    @Column(name = "id", length = 64)
    private String id;
    @Column(name = "model_version", length = 64)
    private String modelVersion;
    @Column(name = "signature", length = 200)
    private String signature;
    @Column(name = "factor", nullable = false)
    private double factor;
    @Column(name = "evidence", columnDefinition = "longtext")
    private String evidence;
}
