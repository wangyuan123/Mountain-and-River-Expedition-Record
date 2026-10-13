package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 即使俘获为零也保留凭证，招募或释放不能使历史战斗再次发放战果。 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "battle_settlements", uniqueConstraints = @UniqueConstraint(columnNames = {"source_kind", "source_id"}))
public class BattleSettlement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "source_kind", nullable = false, length = 30) private String sourceKind;
    @Column(name = "source_id", nullable = false, length = 100) private String sourceId;
    @Column(name = "player_id", nullable = false) private Long playerId;
    @Column(name = "city_id") private Long cityId;
    @Column(name = "created_at", nullable = false) private Long createdAt;
    @Column(columnDefinition = "LONGTEXT") private String snapshot;
}
