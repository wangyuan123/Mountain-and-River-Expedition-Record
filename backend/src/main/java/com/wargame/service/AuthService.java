package com.wargame.service;

import com.wargame.model.UserPrincipal;
import com.wargame.model.entity.Player;
import com.wargame.repository.PlayerRepository;
import com.wargame.security.RateLimiter;
import com.wargame.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

@Service
public class AuthService {

    private final PlayerRepository playerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final GameStateService gameStateService;
    private final RateLimiter rateLimiter;
    private final AccountService accountService;

    @Value("${game.auth.register-per-ip-per-hour:5}")
    private int registerPerHour;
    @Value("${game.auth.login-fail-per-account-per-hour:10}")
    private int loginFailPerAccountPerHour;
    @Value("${game.auth.login-fail-global-per-ip-per-minute:30}")
    private int loginFailGlobalPerMinute;

    public AuthService(PlayerRepository playerRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       GameStateService gameStateService,
                       RateLimiter rateLimiter,
                       AccountService accountService) {
        this.playerRepository = playerRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.gameStateService = gameStateService;
        this.rateLimiter = rateLimiter;
        this.accountService = accountService;
    }

    public AuthResult register(String username, String password, HttpServletRequest req) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        String ip = clientIp(req);
        if (!rateLimiter.allow("REG:" + ip, registerPerHour, 3600_000L)) {
            throw new IllegalStateException("注册过于频繁，请稍后再试");
        }
        if (playerRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已存在");
        }
        if (playerRepository.existsByUsernameAndDisabled(username, 1)) {
            throw new IllegalArgumentException("该用户名已被注销，暂不可用");
        }
        Player player = new Player();
        player.setUsername(username);
        player.setPasswordHash(passwordEncoder.encode(password));
        player.setFaction("allies");
        player.setCityName("新城市");
        player = playerRepository.save(player);
        gameStateService.initializeNewPlayer(player.getId());
        String token = jwtUtil.generateToken(username, player.getId());
        return new AuthResult(token, username, player.getId());
    }

    /** 记录玩家已主动跳过新手引导。 */
    public void dismissTutorial(Long playerId) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
        if (!Boolean.TRUE.equals(player.getTutorialDismissed())) {
            player.setTutorialDismissed(true);
            playerRepository.save(player);
        }
    }

    /** 登录验证只签发恢复凭据，必须由玩家明确确认才撤销注销。 */
    @org.springframework.transaction.annotation.Transactional
    public java.util.Map<String, Object> login(String username, String password, HttpServletRequest req) {
        Player player = verifyCredentials(username, password, req);
        if (!player.accountActive()) return accountService.recoveryRequired(player);
        return java.util.Map.of("token", jwtUtil.generateToken(username, player.getId(), player.getAuthVersion()),
                "username", username, "playerId", player.getId());
    }

    @org.springframework.transaction.annotation.Transactional
    public java.util.Map<String, Object> deletionStatus(String username, String password, HttpServletRequest req) {
        return accountService.status(verifyCredentials(username, password, req));
    }

    private Player verifyCredentials(String username, String password, HttpServletRequest req) {
        accountService.limit("LOGIN_IP:" + clientIp(req), loginFailGlobalPerMinute, 60_000L);
        if (username == null || username.isBlank() || password == null || password.isBlank())
            throw new IllegalArgumentException("用户名或密码错误");
        accountService.limit("LOGIN_ACCOUNT:" + username, loginFailPerAccountPerHour, 3_600_000L);
        Player found = playerRepository.findByUsername(username).orElse(null);
        if (found == null) throw new IllegalArgumentException("用户名或密码错误");
        Player player = accountService.lockPlayer(found.getId());
        if ("DELETED".equals(player.getAccountStatus()) || !passwordEncoder.matches(password, player.getPasswordHash()))
            throw new IllegalArgumentException("用户名或密码错误");
        return player;
    }

    public AuthResult createGuest(HttpServletRequest req) {
        String ip = clientIp(req);
        if (!rateLimiter.allow("GUEST:" + ip, registerPerHour, 3600_000L)) {
            throw new IllegalStateException("游客注册过于频繁，请稍后再试");
        }
        String username = "游客_" + ThreadLocalRandom.current().nextInt(100000, 1000000);
        Player player = new Player();
        player.setUsername(username);
        player.setPasswordHash(passwordEncoder.encode("guest-" + System.currentTimeMillis()));
        player.setFaction("allies");
        player.setCityName("新城市");
        player = playerRepository.save(player);
        gameStateService.initializeNewPlayer(player.getId());
        String token = jwtUtil.generateToken(username, player.getId());
        return new AuthResult(token, username, player.getId());
    }

    public Player getCurrentPlayer() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new RuntimeException("未登录");
        }
        Object principal = auth.getPrincipal();
        if (!(principal instanceof UserPrincipal userPrincipal)) {
            // Defensive: if filter was bypassed or anonymous slipped in, refuse.
            throw new RuntimeException("无法获取当前用户");
        }
        Player player = playerRepository.findById(userPrincipal.getPlayerId())
                .orElseThrow(() -> new RuntimeException("当前用户不存在"));
        if (!player.accountActive() || player.getAuthVersion() != userPrincipal.getAuthVersion())
            throw new com.wargame.security.AccountException("ACCOUNT_UNAVAILABLE", "登录状态已失效，请重新登录");
        return player;
    }

    private static String clientIp(HttpServletRequest req) {
        if (req == null) return "unknown";
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return req.getRemoteAddr() == null ? "unknown" : req.getRemoteAddr();
    }

    public record AuthResult(String token, String username, Long playerId) {}
}
