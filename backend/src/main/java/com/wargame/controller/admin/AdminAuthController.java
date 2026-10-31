package com.wargame.controller.admin;

import com.wargame.service.admin.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String username = body.get("username");
        String password = body.get("password");
        return adminAuthService.login(username, password, request);
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        if (authentication == null) {
            throw new IllegalStateException("未登录");
        }
        return adminAuthService.getProfile(authentication.getName());
    }

    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestBody Map<String, String> body,
                                              Authentication authentication,
                                              HttpServletRequest request) {
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        adminAuthService.changePassword(authentication.getName(), oldPassword, newPassword, request);
        return Map.of("success", true, "message", "密码修改成功，全部后台登录状态已失效，请重新登录", "requiresLogin", true);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(Authentication authentication, HttpServletRequest request) {
        adminAuthService.logout(authentication.getName(), request);
        return Map.of("success", true, "message", "已退出，全部后台登录状态已失效");
    }
}
