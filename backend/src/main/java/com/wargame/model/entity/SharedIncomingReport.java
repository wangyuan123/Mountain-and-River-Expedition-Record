package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.*;

/** 求援时已授权的雷达情报快照，独立于行军生命周期和个人战报收件箱。 */
@Data @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "shared_incoming_reports")
public class SharedIncomingReport {
    @Id @Column(length = 36) private String token;
    @Column(nullable = false) private Long ownerId;
    @Column(nullable = false) private Long guildId;
    @Column(nullable = false, columnDefinition = "longtext") private String data;
    @Column(name = "expires_at", nullable = false) private Long expiresAt;

    /** 兼容现有军情快照创建调用，默认仅有效二十四小时。 */
    public SharedIncomingReport(String token, Long ownerId, Long guildId, String data) {
        this.token = token;
        this.ownerId = ownerId;
        this.guildId = guildId;
        this.data = data;
        this.expiresAt = System.currentTimeMillis() + 24L * 60 * 60 * 1000;
    }
}
