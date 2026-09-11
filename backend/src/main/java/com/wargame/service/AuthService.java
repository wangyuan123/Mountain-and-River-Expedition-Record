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

    public AuthResult login(String username, String password, HttpServletRequest req) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        String ip = clientIp(req);
        // Global per-IP rate cap (defeats distributed credential stuffing)
        if (!rateLimiter.allow("LOGIN_IP:" + ip, loginFailGlobalPerMinute, 60_000L)) {
            throw new IllegalStateException("登录请求过于频繁，请稍后再试");
        }
        Player player = playerRepository.findByUsername(username).orElse(null);
        if (player == null) {
            rateLimiter.allow("LOGIN_FAIL_ACC:" + username, loginFailPerAccountPerHour, 3600_000L);
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (!passwordEncoder.matches(password, player.getPasswordHash())) {
            rateLimiter.allow("LOGIN_FAIL_ACC:" + username, loginFailPerAccountPerHour, 3600_000L);
            throw new IllegalArgumentException("用户名或密码错误");
        }
        // 账号已注销：先尝试在宽限期内自动恢复；已过宽限期则清理并拒绝
        if (player.getDisabled() != null && player.getDisabled() == 1) {
            long disabledAt = player.getDisabledAt() == null ? 0L : player.getDisabledAt();
            long cooldownMs = (long) accountService.getCooldownDays() * 86_400_000L;
            if (System.currentTimeMillis() - disabledAt < cooldownMs) {
                accountService.recoverIfWithinCooldown(player);
            } else {
                accountService.purgeExpiredAccount(player);
                throw new IllegalStateException("账号已注销且超出恢复期，无法登录");
            }
        }
        String token = jwtUtil.generateToken(username, player.getId());
        return new AuthResult(token, username, player.getId());
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
        return playerRepository.findById(userPrincipal.getPlayerId())
                .orElseThrow(() -> new RuntimeException("当前用户不存在"));
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
