package com.wargame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.entity.AdminUser;
import com.wargame.model.entity.Player;
import com.wargame.repository.AdminOperationLogRepository;
import com.wargame.repository.AdminUserRepository;
import com.wargame.security.AdminCredentials;
import com.wargame.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 经真实安全过滤链验收撤销凭据、权限边界和 HTTP 限流；只使用 H2 和合成账号。 */
@SpringBootTest(properties = {
        "game.security.rate-limit.ip-per-minute=20", "game.security.rate-limit.ip-writes-per-minute=10",
        "game.security.rate-limit.account-per-minute=15", "game.security.rate-limit.account-writes-per-minute=5",
        "game.security.rate-limit.admin-login-per-ip-per-minute=3",
        "game.security.rate-limit.admin-login-per-account-per-quarter-hour=3"
})
@AutoConfigureMockMvc
class AdminSecurityIntegrationTest extends BaseServiceTest {
    private static final String PASSWORD = "isolated-admin-original-password";
    private static final AtomicInteger IPS = new AtomicInteger();
    private final String remote = nextIp();
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired AdminUserRepository admins;
    @Autowired AdminOperationLogRepository logs;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtUtil jwt;
    @Autowired jakarta.persistence.EntityManager em;

    private static String nextIp() { return "192.0.2." + IPS.incrementAndGet(); }
    private AdminUser admin() {
        var admin = new AdminUser();
        admin.setUsername("sec-" + UUID.randomUUID());
        admin.setPasswordHash(passwords.encode(PASSWORD));
        admin.setRole("SUPER_ADMIN");
        admin.setCreatedAt(System.currentTimeMillis());
        admin.setUpdatedAt(System.currentTimeMillis());
        return admins.saveAndFlush(admin);
    }
    private String token(AdminUser admin) {
        return jwt.generateAdminToken(admin.getUsername(), admin.getId(), admin.getRole(), admin.getAuthVersion());
    }
    private MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request, String ip) {
        return request.with(value -> { value.setRemoteAddr(ip); return value; });
    }
    private MockHttpServletRequestBuilder profile(String token) {
        return from(get("/api/admin/auth/me").header("Authorization", "Bearer " + token), remote);
    }
    private MockHttpServletRequestBuilder login(String username, String password, String ip) throws Exception {
        return from(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password))), ip);
    }
    private MockHttpServletRequestBuilder action(String path, String token, Map<String, ?> body) throws Exception {
        return from(post("/api/admin/auth/" + path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)), remote);
    }

    @Test void loginIssuesShortVersionedCredentialsAndAuditUsesTheTcpPeer() throws Exception {
        var admin = admin();
        var response = http.perform(login(admin.getUsername(), PASSWORD, remote).header("X-Forwarded-For", "203.0.113.99"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.role").value("SUPER_ADMIN")).andReturn().getResponse();
        String token = json.readTree(response.getContentAsString()).path("token").asText();
        assertEquals(admin.getAuthVersion().longValue(), jwt.getAuthVersion(token));
        var claims = jwt.parseClaims(token);
        assertEquals(7_200_000, claims.getExpiration().getTime() - claims.getIssuedAt().getTime());
        http.perform(profile(token)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value(admin.getUsername()));
        var log = logs.findAll().stream().filter(value -> admin.getUsername().equals(value.getAdminUsername())).findFirst().orElseThrow();
        assertEquals(remote, log.getIpAddress());
        assertFalse(log.getDetail().contains(PASSWORD));
    }

    @Test void unknownDisabledAndWrongPasswordGiveTheSameAuthenticationFailure() throws Exception {
        var admin = admin();
        var unknown = http.perform(login("missing-admin", PASSWORD, remote)).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        var wrong = http.perform(login(admin.getUsername(), "incorrect-password", remote)).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        admin.setDisabled(1);
        admins.saveAndFlush(admin);
        var disabled = http.perform(login(admin.getUsername(), PASSWORD, remote)).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertEquals(unknown, wrong);
        assertEquals(unknown, disabled);
    }

    @Test void passwordChangeRevokesEveryOldSessionAndOnlyTheNewPasswordCanLogin() throws Exception {
        var admin = admin();
        String first = token(admin), second = token(admin);
        long previousVersion = admin.getAuthVersion();
        http.perform(action("change-password", first, Map.of("oldPassword", PASSWORD, "newPassword", "isolated-admin-new-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requiresLogin").value(true));
        em.clear();
        assertTrue(admins.findById(admin.getId()).orElseThrow().getAuthVersion() > previousVersion);
        http.perform(profile(first)).andExpect(status().isUnauthorized());
        http.perform(profile(second)).andExpect(status().isUnauthorized());
        http.perform(login(admin.getUsername(), PASSWORD, remote)).andExpect(status().isUnauthorized());
        var fresh = http.perform(login(admin.getUsername(), "isolated-admin-new-password", remote)).andExpect(status().isOk()).andReturn().getResponse();
        http.perform(profile(json.readTree(fresh.getContentAsString()).path("token").asText())).andExpect(status().isOk());
    }

    @Test void logoutPersistsRevocationAndDoesNotReviveOldDevices() throws Exception {
        var admin = admin();
        String first = token(admin), second = token(admin);
        long previousVersion = admin.getAuthVersion();
        http.perform(action("logout", first, Map.of())).andExpect(status().isOk());
        em.clear();
        var reloaded = admins.findById(admin.getId()).orElseThrow();
        assertTrue(reloaded.getAuthVersion() > previousVersion);
        http.perform(profile(first)).andExpect(status().isUnauthorized());
        http.perform(profile(second)).andExpect(status().isUnauthorized());
        http.perform(profile(token(reloaded))).andExpect(status().isOk());
    }

    @Test void disabledThenReenabledAccountCannotReuseTheOldToken() throws Exception {
        var admin = admin();
        String before = token(admin);
        admin.setDisabled(1);
        admins.saveAndFlush(admin);
        http.perform(profile(before)).andExpect(status().isUnauthorized());
        admin.setDisabled(0);
        admins.saveAndFlush(admin);
        http.perform(profile(before)).andExpect(status().isUnauthorized());
        http.perform(profile(token(admin))).andExpect(status().isOk());
    }

    @Test void permissionChangesAndDeletionInvalidatePreviouslySignedCredentials() throws Exception {
        var admin = admin();
        String before = token(admin);
        admin.setRole("ADMIN");
        admins.saveAndFlush(admin);
        http.perform(profile(before)).andExpect(status().isUnauthorized());
        String downgraded = token(admin);
        http.perform(profile(downgraded)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
        admin.setRole("READ_ONLY");
        admins.saveAndFlush(admin);
        http.perform(profile(token(admin))).andExpect(status().isUnauthorized());
        admins.delete(admin);
        admins.flush();
        http.perform(profile(downgraded)).andExpect(status().isUnauthorized());
    }

    @Test void legacyAndMismatchedTokensCannotCreateAnAdministratorIdentity() throws Exception {
        var admin = admin();
        http.perform(profile(jwt.generateAdminToken(admin.getUsername(), admin.getId(), admin.getRole()))).andExpect(status().isUnauthorized());
        http.perform(profile(jwt.generateAdminToken("different-name", admin.getId(), admin.getRole(), admin.getAuthVersion()))).andExpect(status().isUnauthorized());
        http.perform(profile(jwt.generateAdminToken(admin.getUsername(), Long.MAX_VALUE, admin.getRole(), admin.getAuthVersion()))).andExpect(status().isUnauthorized());
        admin.setPasswordHash(AdminCredentials.LEGACY_DEFAULT_HASH);
        admins.saveAndFlush(admin);
        http.perform(profile(token(admin))).andExpect(status().isUnauthorized());
        http.perform(login(admin.getUsername(), "admin123456", remote)).andExpect(status().isUnauthorized());
    }

    @Test void playerCredentialsCannotCallAdministratorEndpoints() throws Exception {
        Player player = new Player();
        player.setUsername("security-player");
        player.setPasswordHash("isolated-unused-hash");
        player = playerRepository.saveAndFlush(player);
        String token = jwt.generateToken(player.getUsername(), player.getId(), player.getAuthVersion());
        http.perform(profile(token)).andExpect(status().isForbidden());
        http.perform(from(get("/api/admin/auth/login"), remote)).andExpect(status().isUnauthorized());
    }

    @Test void rotatingUsernamesAndSpoofedForwardingHeadersCannotBypassLoginIpQuota() throws Exception {
        for (int i = 0; i < 3; i++)
            http.perform(login("unknown-" + i, PASSWORD, remote).header("X-Forwarded-For", "203.0.113." + i)).andExpect(status().isUnauthorized());
        http.perform(login("another-unknown", PASSWORD, remote).header("X-Forwarded-For", "203.0.113.99"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(jsonPath("$.retryAfter").isNumber());
    }

    @Test void rotatingIpsAndUsernameCaseCannotBypassLoginAccountQuota() throws Exception {
        String username = "missing-" + UUID.randomUUID();
        for (int i = 0; i < 3; i++)
            http.perform(login(i % 2 == 0 ? username : username.toUpperCase(java.util.Locale.ROOT), PASSWORD, nextIp())).andExpect(status().isUnauthorized());
        http.perform(login(username, PASSWORD, nextIp())).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.retryAfter").isNumber());
    }

    @Test void anonymousReadsAreLimitedAcrossAllPublicApiPaths() throws Exception {
        for (int i = 0; i < 20; i++)
            http.perform(from(get("/api/auth/server?attempt=" + i), remote)).andExpect(status().isOk());
        http.perform(from(get("/api/forum/boards"), remote)).andExpect(status().isTooManyRequests());
    }

    @Test void registrationQuotaUsesTrustedSourceAndReturns429InsteadOfMaintenance() throws Exception {
        String body = json.writeValueAsString(Map.of("username", "limited-register", "password", "isolated-register-password",
                "agreementVersion", com.wargame.service.AuthService.CURRENT_REGISTRATION_AGREEMENT_VERSION,
                "serverId", "jiangsu-1", "email", "isolated-register@qq.com", "challengeId", "unverified", "emailCode", "000000"));
        for (int i = 0; i < 5; i++)
            http.perform(from(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)
                    .header("X-Forwarded-For", "203.0.113." + i), remote)).andExpect(status().isBadRequest());
        http.perform(from(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Forwarded-For", "203.0.113.99"), remote))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        assertFalse(playerRepository.existsByUsername("limited-register"));
    }

    @Test void accountQuotaSurvivesIpAndTokenRotation() throws Exception {
        var admin = admin();
        for (int i = 0; i < 15; i++)
            http.perform(from(get("/api/admin/auth/me").header("Authorization", "Bearer " + token(admin)), nextIp())).andExpect(status().isOk());
        http.perform(from(get("/api/admin/players").header("Authorization", "Bearer " + token(admin)), nextIp()))
                .andExpect(status().isTooManyRequests());
    }

    @Test void invalidPasswordChangesDoNotInvalidateTheCurrentCredential() throws Exception {
        var admin = admin();
        String token = token(admin);
        for (String invalid : new String[]{"too-short", "a".repeat(65), "中".repeat(25), PASSWORD})
            http.perform(action("change-password", token, Map.of("oldPassword", PASSWORD, "newPassword", invalid))).andExpect(status().isBadRequest());
        http.perform(profile(token)).andExpect(status().isOk());
    }
}
