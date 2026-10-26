package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@lombok.EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "radar_monitors")
public class RadarMonitor extends VersionedEntity {
    @Id
    private Long playerId;
    @Column(nullable = false)
    private long revision;
}
