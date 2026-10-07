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
}
