package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(com.wargame.service.RetentionEntityListener.class)
@Table(name = "scout_reports")
public class ScoutReport {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

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

    @Column(name = "persisted_real_at")
    private Long persistedRealAt;

    @Column(name = "detail_expires_at")
    private Long detailExpiresAt;

    @Column(name = "summary_expires_at")
    private Long summaryExpiresAt;

    @Column(name = "summary_json", columnDefinition = "longtext")
    private String summaryJson;

    @Column(name = "list_summary_json", columnDefinition = "TEXT")
    private String listSummaryJson;

    @Column(name = "summary_schema_version", nullable = false)
    private Integer summarySchemaVersion = 1;

    @Column(name = "starred_at")
    private Long starredAt;

    /** 保存完整快照时同步生成摘要，避免裁剪时依赖当前配置重建历史战果。 */
    public void setData(String data) {
        this.data = data;
        this.summaryJson = com.wargame.service.ReportSummary.fromJson(data);
        this.listSummaryJson = com.wargame.service.ReportSummary.cardJson(data);
    }

    /** 新战报的生命周期以真实保存时间计算，不依赖可暂停的游戏时钟时间戳。 */
    @PrePersist
    private void initializeRetention() {
        long now = System.currentTimeMillis();
        if (persistedRealAt == null) persistedRealAt = now;
        if (detailExpiresAt == null) detailExpiresAt = persistedRealAt + 30 * DAY_MS;
        if (summaryExpiresAt == null) summaryExpiresAt = detailExpiresAt + 30 * DAY_MS;
        if (summaryJson == null) summaryJson = com.wargame.service.ReportSummary.fromJson(data);
        if (listSummaryJson == null) listSummaryJson = com.wargame.service.ReportSummary.cardJson(summaryJson);
    }
}
