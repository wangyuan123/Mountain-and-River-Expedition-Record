package com.wargame.controller;

import com.wargame.model.dto.AuthDtos;
import com.wargame.model.entity.Player;
import com.wargame.service.AccountService;
import com.wargame.service.AuthService;
import com.wargame.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final AccountService accountService;

    public AuthController(AuthService authService, JwtUtil jwtUtil, AccountService accountService) {
        this.authService = authService;
        this.jwtUtil = jwtUtil;
        this.accountService = accountService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthDtos.AuthResponse> register(@RequestBody AuthDtos.RegisterRequest request,
                                                           HttpServletRequest httpRequest) {
        AuthService.AuthResult result = authService.register(request.username(), request.password(), httpRequest);
        return ResponseEntity.ok(new AuthDtos.AuthResponse(result.token(), result.username(), result.playerId()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.AuthResponse> login(@RequestBody AuthDtos.LoginRequest request,
                                                        HttpServletRequest httpRequest) {
        AuthService.AuthResult result = authService.login(request.username(), request.password(), httpRequest);
        return ResponseEntity.ok(new AuthDtos.AuthResponse(result.token(), result.username(), result.playerId()));
    }

    @PostMapping("/guest")
    public ResponseEntity<AuthDtos.AuthResponse> guest(HttpServletRequest httpRequest) {
        AuthService.AuthResult result = authService.createGuest(httpRequest);
        return ResponseEntity.ok(new AuthDtos.AuthResponse(result.token(), result.username(), result.playerId()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            jwtUtil.revoke(header.substring(7));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /** 记录当前玩家已跳过新手引导，状态持久化到账号。 */
    @PostMapping("/tutorial/dismiss")
    public ResponseEntity<Map<String, Object>> dismissTutorial() {
        Player player = authService.getCurrentPlayer();
        authService.dismissTutorial(player.getId());
        return ResponseEntity.ok(Map.of("success", true, "tutorialDismissed", true));
    }

    @GetMapping("/me")
    public ResponseEntity<AuthDtos.UserInfoResponse> me() {
        Player player = authService.getCurrentPlayer();
        return ResponseEntity.ok(new AuthDtos.UserInfoResponse(
                player.getId(), player.getUsername(), player.getFaction(), player.getCityName()));
    }

    /**
     * 注销当前登录账号。
     * - 验证密码
     * - 验证前端 confirm 字段 == "确认注销"
     * - 标记 disabled=1
     * - 撤销当前 Token
     */
    @PostMapping("/disable")
    public ResponseEntity<Map<String, Object>> disable(@RequestBody AuthDtos.DisableAccountRequest request,
                                                        HttpServletRequest httpRequest) {
        Player player = authService.getCurrentPlayer();
        if (request.confirm() == null || !"确认注销".equals(request.confirm().trim())) {
            throw new IllegalArgumentException("请输入\"确认注销\"以完成操作");
        }
        String token = null;
        String header = httpRequest.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7);
        }
        Map<String, Object> result = accountService.disableAccount(player.getId(), request.password(), token);
        return ResponseEntity.ok(result);
    }
}
