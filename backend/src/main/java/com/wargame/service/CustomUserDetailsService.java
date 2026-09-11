package com.wargame.service;

import com.wargame.model.UserPrincipal;
import com.wargame.model.entity.Player;
import com.wargame.repository.PlayerRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final PlayerRepository playerRepository;

    public CustomUserDetailsService(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Player player = playerRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));
        return UserPrincipal.from(player);
    }
}
