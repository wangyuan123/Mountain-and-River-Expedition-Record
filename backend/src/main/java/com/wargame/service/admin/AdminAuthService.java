package com.wargame.service.admin;

import com.wargame.model.entity.AdminUser;
import com.wargame.repository.AdminUserRepository;
import com.wargame.util.JwtUtil;
import com.wargame.security.AdminCredentials;
import com.wargame.security.RequestRateLimits;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AdminAuthService {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AdminAuditLogService auditLogService;
    private final RequestRateLimits limits;
    private final String dummyPasswordHash;

    public AdminAuthService(AdminUserRepository adminUserRepository,
                            PasswordEncoder passwordEncoder,
                            JwtUtil jwtUtil,
                            AdminAuditLogService auditLogService, RequestRateLimits limits) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.auditLogService = auditLogService;
        this.limits = limits;
        this.dummyPasswordHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
    }

    /** IP 入口配额之外，再按账号限制所有登录尝试；账号不存在或已禁用时仍执行一次密码校验。 */
    @Transactional
    public Map<String, Object> login(String username, String password, HttpServletRequest request) {
        if (username == null || username.isBlank() || username.trim().length() > 50
                || password == null || password.isBlank() || password.length() > 64
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw invalidLogin();
        limits.checkAdminLogin(username);
        AdminUser admin = adminUserRepository.findByUsername(username.trim()).orElse(null);
        boolean passwordMatches = passwordEncoder.matches(password, admin == null ? dummyPasswordHash : admin.getPasswordHash());
        if (!passwordMatches || admin == null || !admin.isActive() || !AdminCredentials.allowedRole(admin.getRole())
                || AdminCredentials.LEGACY_DEFAULT_HASH.equals(admin.getPasswordHash())) throw invalidLogin();

        String token = jwtUtil.generateAdminToken(admin.getUsername(), admin.getId(), admin.getRole(), admin.getAuthVersion());
        auditLogService.record(admin.getUsername(), "ADMIN_LOGIN", "ADMIN", String.valueOf(admin.getId()), "管理员登录成功", request);

        return Map.of(
                "token", token,
                "adminId", admin.getId(),
                "username", admin.getUsername(),
                "role", admin.getRole()
        );
    }

    public Map<String, Object> getProfile(String username) {
        AdminUser admin = adminUserRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("管理员不存在"));
        return Map.of(
                "adminId", admin.getId(),
                "username", admin.getUsername(),
                "role", admin.getRole(),
                "createdAt", admin.getCreatedAt()
        );
    }

    @Transactional
    public void changePassword(String username, String oldPassword, String newPassword, HttpServletRequest request) {
        AdminUser admin = adminUserRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("管理员不存在"));
        if (oldPassword == null || !passwordEncoder.matches(oldPassword, admin.getPasswordHash())) {
            throw new IllegalArgumentException("旧密码不正确");
        }
        AdminCredentials.validateNewPassword(newPassword);
        if (passwordEncoder.matches(newPassword, admin.getPasswordHash())) throw new IllegalArgumentException("新密码不能与旧密码相同");
        admin.setPasswordHash(passwordEncoder.encode(newPassword));
        admin.setUpdatedAt(System.currentTimeMillis());
        // flush 推进持久化版本，响应成功时所有旧设备 JWT 已失效，重启后也不会恢复。
        adminUserRepository.saveAndFlush(admin);
        auditLogService.record(admin.getUsername(), "CHANGE_PASSWORD", "ADMIN", String.valueOf(admin.getId()), "修改密码", request);
    }

    /** 退出后台即撤销该管理员的全部会话，不依赖仅存于内存的 JWT 黑名单。 */
    @Transactional
    public void logout(String username, HttpServletRequest request) {
        AdminUser admin = adminUserRepository.findByUsername(username).orElseThrow(AdminAuthService::invalidLogin);
        // 保证即使同一毫秒内重复操作也有脏字段，由 JPA 原子递增 @Version，不能手工改写版本字段。
        admin.setUpdatedAt(Math.max(admin.getUpdatedAt() + 1, System.currentTimeMillis()));
        adminUserRepository.saveAndFlush(admin);
        auditLogService.record(username, "ADMIN_LOGOUT", "ADMIN", String.valueOf(admin.getId()), "退出并撤销全部后台会话", request);
    }

    private static BadCredentialsException invalidLogin() {
        return new BadCredentialsException("管理员账号或密码错误，或账号不可用");
    }
}
