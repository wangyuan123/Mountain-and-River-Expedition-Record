package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 招募与释放共用玩家级操作键；相同键只允许重放完全相同的请求。 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "prisoner_operations", uniqueConstraints = @UniqueConstraint(columnNames = {"player_id", "operation_key"}))
public class PrisonerOperation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "player_id", nullable = false) private Long playerId;
    @Column(name = "city_id", nullable = false) private Long cityId;
    @Column(name = "operation_key", nullable = false, length = 80) private String operationKey;
    @Column(nullable = false, length = 20) private String action;
    @Column(nullable = false, length = 50) private String type;
    @Column(nullable = false) private Integer count;
    @Column(name = "created_at", nullable = false) private Long createdAt;
    @Column(nullable = false, columnDefinition = "LONGTEXT") private String result;
}
