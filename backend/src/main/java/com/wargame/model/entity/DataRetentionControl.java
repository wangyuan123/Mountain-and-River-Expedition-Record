package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.Data;

/** 单例维护租约，同时记录轮转位置；默认只观察，不作物理清理。 */
@Data
@Entity
@Table(name = "data_retention_control")
public class DataRetentionControl {
    @Id private Integer controlId;
    @Column(nullable = false) private long observationStartedAt;
    @Column(length = 36) private String leaseOwner;
    @Column(nullable = false) private long leaseUntil;
    @Column(nullable = false) private long lastRunAt;
    @Column(nullable = false) private boolean cleanupEnabled;
    @Column(nullable = false) private int nextCategory;
    @Column(nullable = false) private long backfillCursor;
    @Column(nullable = false) private long detailCursor;
}
