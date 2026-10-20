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
}
