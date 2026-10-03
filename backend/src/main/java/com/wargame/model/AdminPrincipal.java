package com.wargame.model;

import com.wargame.model.entity.AdminUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public class AdminPrincipal implements UserDetails {

    private final Long adminId;
    private final String username;
    private final String password;
    private final String role;
    private final boolean disabled;

    public AdminPrincipal(Long adminId, String username, String password, String role, boolean disabled) {
        this.adminId = adminId;
        this.username = username;
        this.password = password;
        this.role = role != null ? role : "ADMIN";
        this.disabled = disabled;
    }

    public static AdminPrincipal from(AdminUser admin) {
        return new AdminPrincipal(admin.getId(), admin.getUsername(), admin.getPasswordHash(), admin.getRole(), !admin.isActive());
    }

    public Long getAdminId() {
        return adminId;
    }

    public String getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !disabled;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return !disabled;
    }
}
