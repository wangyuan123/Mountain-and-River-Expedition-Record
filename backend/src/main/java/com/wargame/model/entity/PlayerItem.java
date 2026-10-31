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
@Table(name = "player_items",
       uniqueConstraints = @UniqueConstraint(name = "uq_player_item", columnNames = {"player_id", "item_key"}))
public class PlayerItem extends VersionedEntity {

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
    /** 库存不能写入负数；所有历史发奖入口也遵守同一持久化边界。 */
    public void setCount(Integer value) {
        if (value != null && value < 0) throw new IllegalArgumentException("道具库存超出可保存范围");
        count = value;
    }

}
