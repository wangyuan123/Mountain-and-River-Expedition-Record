package com.wargame.service.admin;

import com.wargame.model.entity.AdminUser;
import com.wargame.repository.AdminUserRepository;
import com.wargame.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AdminAuthService {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AdminAuditLogService auditLogService;

    public AdminAuthService(AdminUserRepository adminUserRepository,
                            PasswordEncoder passwordEncoder,
                            JwtUtil jwtUtil,
                            AdminAuditLogService auditLogService) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public Map<String, Object> login(String username, String password, HttpServletRequest request) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }
        AdminUser admin = adminUserRepository.findByUsername(username.trim())
                .orElseThrow(() -> new IllegalArgumentException("管理员账号不存在或密码错误"));

        if (!admin.isActive()) {
            throw new IllegalStateException("该管理员账号已被禁用");
        }

        if (!passwordEncoder.matches(password, admin.getPasswordHash())) {
            throw new IllegalArgumentException("管理员账号不存在或密码错误");
        }

        String token = jwtUtil.generateAdminToken(admin.getUsername(), admin.getId(), admin.getRole());
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
        if (!passwordEncoder.matches(oldPassword, admin.getPasswordHash())) {
            throw new IllegalArgumentException("旧密码不正确");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("新密码长度不能少于6位");
        }
        admin.setPasswordHash(passwordEncoder.encode(newPassword));
        admin.setUpdatedAt(System.currentTimeMillis());
        adminUserRepository.save(admin);
        auditLogService.record(admin.getUsername(), "CHANGE_PASSWORD", "ADMIN", String.valueOf(admin.getId()), "修改密码", request);
    }
}
