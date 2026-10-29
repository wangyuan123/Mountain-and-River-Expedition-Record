package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;

/** 动员使用回执：相同请求只扣一次库存，同一标识不能改成另一种使用操作。 */
@Data
@Entity
@Table(name = "mobilization_uses")
public class MobilizationUse {
    @Id
    @Column(length = 100)
    private String id;
    @Column(name = "player_id", nullable = false)
    private Long playerId;
    @Column(name = "item_id", nullable = false, length = 40)
    private String itemId;
    @Column(nullable = false)
    private int amount;
    @Column(name = "used_at", nullable = false)
    private long usedAt;
}
