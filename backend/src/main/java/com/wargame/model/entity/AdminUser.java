package com.wargame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "admin_users")
public class AdminUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", unique = true, nullable = false, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** 所有管理员资料更新均推进版本，使改密、禁用、改权限前的 JWT 永久失效，并防止并发覆盖。 */
    @Version
    @Column(name = "auth_version", nullable = false)
    private Long authVersion = 1L;

    @Column(name = "role", nullable = false, length = 20)
    private String role = "ADMIN";

    @Column(name = "disabled", nullable = false)
    private Integer disabled = 0;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    public boolean isActive() {
        return Integer.valueOf(0).equals(disabled);
    }
}
