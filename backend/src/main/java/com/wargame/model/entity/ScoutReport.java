package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "scout_reports")
public class ScoutReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "target_x")
    private Integer targetX;

    @Column(name = "target_y")
    private Integer targetY;

    @Column(name = "target_name", length = 100)
    private String targetName;

    @Column(name = "data", columnDefinition = "longtext")
    private String data;

    /** 战报类型: scout=侦查 / battle=战斗(征服/掠夺/野地战) */
    @Column(name = "type", length = 20)
    private String type = "scout";

    @Column(name = "created_at")
    private Long createdAt;

    /** 首次被玩家阅读的时间戳（毫秒），0 表示未读 */
    @Column(name = "read_at")
    private Long readAt = 0L;
}
