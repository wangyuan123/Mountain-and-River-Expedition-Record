package com.wargame.controller.account;

import com.wargame.config.GameServerIdentity;
import com.wargame.service.AccountEmailService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 账号安全操作独立于游戏时间许可，在服务内部重新取得账号锁与验证会话。 */
@RestController
@RequestMapping("/api/auth")
public class AccountEmailController {
    private final AccountEmailService emails;
    private final GameServerIdentity server;
    @org.springframework.beans.factory.annotation.Autowired private com.wargame.security.ClientIpResolver clientIps;
    public AccountEmailController(AccountEmailService emails, GameServerIdentity server) { this.emails = emails; this.server = server; }

    public record EmailRequest(String username, String email, String serverId, String currentPassword,
                               String challengeId, String emailCode, String newPassword, String newEmail, String changeToken) {}

    @ModelAttribute
    void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping("/email") public Map<String, Object> status() { return emails.status(); }
    @PostMapping("/register/email-code") public Map<String, Object> registerCode(@RequestBody EmailRequest body, HttpServletRequest request) {
        server.requireSelected(body.serverId()); return emails.registrationCode(body.username(), body.email(), clientIps.resolve(request));
    }
    @PostMapping("/email/bind-code") public Map<String, Object> bindCode(@RequestBody EmailRequest body, HttpServletRequest request) {
        return emails.bindCode(body.currentPassword(), body.email(), clientIps.resolve(request));
    }
    @PostMapping("/email/bind") public Map<String, Object> bind(@RequestBody EmailRequest body) {
        return emails.bind(body.currentPassword(), body.email(), body.challengeId(), body.emailCode());
    }
    @PostMapping("/password/reset-code") public Map<String, Object> resetCode(@RequestBody EmailRequest body, HttpServletRequest request) {
        server.requireSelected(body.serverId()); return emails.resetCode(body.username(), body.email(), clientIps.resolve(request));
    }
    @PostMapping("/password/reset") public Map<String, Object> reset(@RequestBody EmailRequest body, HttpServletRequest request) {
        server.requireSelected(body.serverId());
        return emails.resetPassword(body.username(), body.email(), body.challengeId(), body.emailCode(), body.newPassword(), clientIps.resolve(request));
    }
    @PostMapping("/email/change/old-code") public Map<String, Object> oldCode(@RequestBody EmailRequest body, HttpServletRequest request) {
        return emails.oldCode(body.currentPassword(), clientIps.resolve(request));
    }
    @PostMapping("/email/change/authorize") public Map<String, Object> authorize(@RequestBody EmailRequest body) {
        return emails.authorizeChange(body.currentPassword(), body.newEmail(), body.challengeId(), body.emailCode());
    }
    @PostMapping("/email/change/new-code") public Map<String, Object> newCode(@RequestBody EmailRequest body, HttpServletRequest request) {
        return emails.newCode(body.changeToken(), clientIps.resolve(request));
    }
    @PostMapping("/email/change") public Map<String, Object> change(@RequestBody EmailRequest body) {
        return emails.completeChange(body.changeToken(), body.challengeId(), body.emailCode());
    }
}
