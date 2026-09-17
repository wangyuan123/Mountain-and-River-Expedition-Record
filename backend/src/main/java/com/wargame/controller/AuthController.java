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
    @org.springframework.beans.factory.annotation.Autowired
    private com.wargame.service.compliance.AntiAddictionService protection;

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
    public ResponseEntity<Map<String, Object>> login(@RequestBody AuthDtos.LoginRequest request,
                                                        HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.login(request.username(), request.password(), httpRequest));
    }

    @PostMapping("/guest")
    public ResponseEntity<AuthDtos.AuthResponse> guest(HttpServletRequest httpRequest) {
        AuthService.AuthResult result = authService.createGuest(httpRequest);
        return ResponseEntity.ok(new AuthDtos.AuthResponse(result.token(), result.username(), result.playerId()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest request) {
        // 主动退出及时结算许可；丢失退出请求仍由短租约停止计时。
        protection.end(authService.getCurrentPlayer(), request.getHeader("X-Play-Session"));
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

    @GetMapping("/deletion-preview")
    public ResponseEntity<Map<String, Object>> deletionPreview() {
        return ResponseEntity.ok(accountService.preview(authService.getCurrentPlayer().getId()));
    }

    /** 密码确认后受理注销，不接受客户端传入的玩家编号或截止时间。 */
    @PostMapping("/disable")
    public ResponseEntity<Map<String, Object>> disable(@RequestBody AuthDtos.DisableAccountRequest request) {
        Player player = authService.getCurrentPlayer();
        return ResponseEntity.ok(accountService.disableAccount(player.getId(), request.password(), request.confirm(), request.requestId()));
    }

    @PostMapping("/recover")
    public ResponseEntity<Map<String, Object>> recover(@RequestBody AuthDtos.RecoverAccountRequest request,
                                                      HttpServletRequest httpRequest) {
        accountService.limit("RECOVERY_IP:" + httpRequest.getRemoteAddr(), 30, 60_000);
        return ResponseEntity.ok(accountService.recover(request.recoveryToken(), request.confirm()));
    }

    @PostMapping("/deletion-status")
    public ResponseEntity<Map<String, Object>> deletionStatus(@RequestBody AuthDtos.LoginRequest request,
                                                             HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.deletionStatus(request.username(), request.password(), httpRequest));
    }
}
