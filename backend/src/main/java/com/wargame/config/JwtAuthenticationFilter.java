package com.wargame.config;

import com.wargame.service.CustomUserDetailsService;
import com.wargame.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;
    private final GameServerIdentity server;
    private final com.wargame.repository.AdminUserRepository admins;
    private final com.wargame.security.RequestRateLimits limits;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, CustomUserDetailsService userDetailsService,
                                   GameServerIdentity server, com.wargame.repository.AdminUserRepository admins,
                                   com.wargame.security.RequestRateLimits limits) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.server = server;
        this.admins = admins;
        this.limits = limits;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/auth/") || request.getRequestURI().startsWith("/api/admin/"))
            response.setHeader("Cache-Control", "no-store");
        if (!limits.allowIp(request, response)) return;
        if (request.getRequestURI().startsWith("/api/") && !"OPTIONS".equals(request.getMethod())) {
            try { server.requireSelected(request.getHeader("X-Game-Server")); }
            catch (IllegalArgumentException error) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, error.getMessage());
                return;
            }
        }
        String token = extractToken(request);
        if (StringUtils.hasText(token) && jwtUtil.validateToken(token)) {
            try {
                if (jwtUtil.isAdminToken(token)) {
                    String username = jwtUtil.getUsernameFromToken(token);
                    Long adminId = jwtUtil.getAdminIdFromToken(token);
                    var admin = adminId == null ? null : admins.findById(adminId).orElse(null);
                    // 不复用 JWT 内的权限：停用、删除、改密、改权限及退出后均以当前数据库状态为准。
                    if (admin == null || !admin.isActive() || !admin.getUsername().equals(username)
                            || !com.wargame.security.AdminCredentials.allowedRole(admin.getRole())
                            || com.wargame.security.AdminCredentials.LEGACY_DEFAULT_HASH.equals(admin.getPasswordHash())
                            || !admin.getRole().equals(jwtUtil.getRoleFromToken(token))
                            || admin.getAuthVersion() == null || admin.getAuthVersion() != jwtUtil.getAuthVersion(token)) {
                        SecurityContextHolder.clearContext();
                    } else {
                        var principal = com.wargame.model.AdminPrincipal.from(admin);
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities());
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                } else {
                    String username = jwtUtil.getUsernameFromToken(token);
                    UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                    if (!(userDetails instanceof com.wargame.model.UserPrincipal principal)
                            || !userDetails.isEnabled() || !userDetails.isAccountNonLocked()
                            || principal.getAuthVersion() != jwtUtil.getAuthVersion(token)
                            || !principal.getPlayerId().equals(jwtUtil.getPlayerIdFromToken(token))) {
                        // 账号已被注销或封号，禁止继续访问
                        SecurityContextHolder.clearContext();
                    } else {
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                }
            } catch (Exception e) {
                SecurityContextHolder.clearContext();
            }
        }
        if (!limits.allowAccount(request, response, SecurityContextHolder.getContext().getAuthentication())) return;
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
