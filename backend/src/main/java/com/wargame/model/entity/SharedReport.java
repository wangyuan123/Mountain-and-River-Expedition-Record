package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.*;

/** 随机分享凭证绑定持有人战报，不公开可枚举的战报 ID。 */
@Data @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "shared_reports")
public class SharedReport {
    @Id @Column(length = 36) private String token;
    @Column(nullable = false) private Long reportId;
    @Column(nullable = false) private Long ownerId;
    @Column(name = "expires_at", nullable = false) private Long expiresAt;

    /** 兼容既有分享创建调用，默认凭证有效七天。 */
    public SharedReport(String token, Long reportId, Long ownerId) {
        this.token = token;
        this.reportId = reportId;
        this.ownerId = ownerId;
        this.expiresAt = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000;
    }
}
