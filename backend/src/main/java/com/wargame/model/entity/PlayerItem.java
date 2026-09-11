package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "player_items",
       uniqueConstraints = @UniqueConstraint(name = "uq_player_item", columnNames = {"player_id", "item_key"}))
public class PlayerItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "item_key", nullable = false, length = 50)
    private String itemKey;

    @Column(name = "count", nullable = false)
    private Integer count;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
