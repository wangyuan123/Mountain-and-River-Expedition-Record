package com.wargame.model;

import com.wargame.model.entity.Player;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public class UserPrincipal implements UserDetails {

    private final Long playerId;
    private final String username;
    private final String password;
    private final boolean disabled;
    private long authVersion;

    public UserPrincipal(Long playerId, String username, String password, boolean disabled) {
        this.playerId = playerId;
        this.username = username;
        this.password = password;
        this.disabled = disabled;
    }

    public static UserPrincipal from(Player player) {
        UserPrincipal principal = new UserPrincipal(player.getId(), player.getUsername(), player.getPasswordHash(), !player.accountActive());
        principal.authVersion = player.getAuthVersion();
        return principal;
    }

    public long getAuthVersion() { return authVersion; }

    public Long getPlayerId() {
        return playerId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
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
    public boolean isEnabled() {
        return !disabled;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
