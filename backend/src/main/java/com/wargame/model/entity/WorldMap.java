package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "world_map")
public class WorldMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "size")
    private Integer size;

    @Column(name = "scan_radius")
    private Integer scanRadius;

    @Column(name = "pos_x")
    private Integer posX;

    @Column(name = "pos_y")
    private Integer posY;
}
