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
@Table(name = "wild_tiles")
public class WildTile extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "world_id", nullable = false)
    private Long worldId;

    @Column(name = "type", nullable = false, length = 50)
    private String type;

    @Column(name = "x", nullable = false)
    private Integer x;

    @Column(name = "y", nullable = false)
    private Integer y;

    @Column(name = "level")
    private Integer level;

    @Column(name = "garrison", columnDefinition = "text")
    private String garrison;

    @Column(name = "scouted")
    private Boolean scouted;

    @Column(name = "occupied")
    private Boolean occupied;

    @Column(name = "occupied_by")
    private Long occupiedBy;

    @Column(name = "total_res")
    private Integer totalRes;

    @Column(name = "mined")
    private Integer mined;

    @Column(name = "gathering")
    private Boolean gathering;

    @Column(name = "gather_start_at")
    private Long gatherStartAt;

    @Column(name = "gather_end_at")
    private Long gatherEndAt;

    @Column(name = "gather_load")
    private Integer gatherLoad;

    @Column(name = "gather_res", length = 50)
    private String gatherRes;
}
