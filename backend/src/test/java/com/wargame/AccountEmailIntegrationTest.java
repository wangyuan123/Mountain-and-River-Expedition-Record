package com.wargame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.entity.Player;
import com.wargame.service.AccountEmailVault;
import com.wargame.service.AccountService;
import com.wargame.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 只使用内存数据库和替身邮件投递，验证账号安全流程与存档保持。 */
@AutoConfigureMockMvc
class AccountEmailIntegrationTest extends BaseServiceTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JwtUtil jwt;
    @Autowired PasswordEncoder passwords;
    @Autowired AccountEmailVault vault;
    @Autowired AccountService accounts;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.wargame.repository.AdminOperationLogRepository auditLogs;

    private String token(Player player) { return jwt.generateToken(player.getUsername(), player.getId(), player.getAuthVersion()); }
    private Player oldAccount(String name, String email) {
        Player player = createTestPlayer(name, 30);
        player.setPasswordHash(passwords.encode("original-password"));
        if (email != null) {
            player.setRecoveryEmailCipher(vault.encrypt(email));
            player.setRecoveryEmailKey(vault.digest("email|jiangsu-1|" + email));
            player.setRecoveryEmailVersion(1); player.setRecoveryEmailVerifiedAt(System.currentTimeMillis());
        }
        return playerRepository.saveAndFlush(player);
    }
    private JsonNode post(String path, String token, Map<String, ?> body) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth" + path)
                .header("X-Game-Server", "jiangsu-1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return json.readTree(http.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test void verifiedRegistrationBindsAnEncryptedRecoveryAddress() throws Exception {
        createTestWorld();
        Map<String, Object> fields = registrationFields("email-registration", "initial-password");
        JsonNode result = post("/register", null, fields);
        Player player = playerRepository.findById(result.path("playerId").asLong()).orElseThrow();
        assertEquals(fields.get("email"), vault.decrypt(player.getRecoveryEmailCipher()));
        assertNotEquals(fields.get("email"), player.getRecoveryEmailCipher());
        http.perform(get("/api/auth/email").header("Authorization", "Bearer " + result.path("token").asText()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.emailBound").value(true));
        assertFalse(json.writeValueAsString(player).contains("recoveryEmailCipher"));
        assertFalse(json.writeValueAsString(player).contains("passwordHash"));
        var event = auditLogs.findAll().stream().filter(log -> "ACCOUNT_EMAIL_BIND".equals(log.getActionType())).findFirst().orElseThrow();
        assertEquals(player.getId().toString(), event.getTargetId());
        assertFalse(event.getDetail().contains((String) fields.get("email")));
        assertFalse(event.getDetail().contains("initial-password"));
    }

    @Test void existingAccountBindsWithoutChangingItsPasswordOrResources() throws Exception {
        Player player = oldAccount("email-legacy", null);
        String token = token(player), email = "isolated-bind@qq.com", hash = player.getPasswordHash();
        long food = getResources(player.getId()).getFood();
        JsonNode sent = post("/email/bind-code", token, Map.of("currentPassword", "original-password", "email", email));
        JsonNode bound = post("/email/bind", token, Map.of("currentPassword", "original-password", "email", email,
                "challengeId", sent.path("challengeId").asText(), "emailCode", deliveredCode(email)));
        assertTrue(bound.path("emailBound").asBoolean());
        assertEquals("is***nd@qq.com", bound.path("maskedEmail").asText());
        assertEquals(hash, player.getPasswordHash()); assertEquals(0, player.getAuthVersion());
        assertEquals(food, getResources(player.getId()).getFood());
    }

    @Test void passwordResetPreservesSaveAndRequiresFreshLogin() throws Exception {
        String email = "isolated-reset@qq.com";
        Player player = oldAccount("email-password", email);
        String previous = token(player); long food = getResources(player.getId()).getFood();
        JsonNode sent = post("/password/reset-code", null, Map.of("serverId", "jiangsu-1", "username", player.getUsername(), "email", email));
        JsonNode result = post("/password/reset", null, Map.of("serverId", "jiangsu-1", "username", player.getUsername(),
                "email", email, "challengeId", sent.path("challengeId").asText(), "emailCode", deliveredCode(email), "newPassword", "changed-password"));
        assertTrue(result.path("success").asBoolean()); assertFalse(result.has("token"));
        assertTrue(passwords.matches("changed-password", player.getPasswordHash()));
        assertEquals(1L, player.getAuthVersion()); assertEquals(food, getResources(player.getId()).getFood());
        http.perform(get("/api/auth/me").header("Authorization", "Bearer " + previous)).andExpect(status().isUnauthorized());
        assertTrue(post("/login", null, Map.of("serverId", "jiangsu-1", "username", player.getUsername(), "password", "changed-password")).has("token"));
    }

    @Test void changingEmailVerifiesBothMailboxesThenReplacesTheRecoveryAddress() throws Exception {
        String oldEmail = "isolated-old@qq.com", newEmail = "isolated-new@qq.com";
        Player player = oldAccount("email-change", oldEmail); String token = token(player);
        JsonNode old = post("/email/change/old-code", token, Map.of("currentPassword", "original-password"));
        JsonNode authorized = post("/email/change/authorize", token, Map.of("currentPassword", "original-password", "newEmail", newEmail,
                "challengeId", old.path("challengeId").asText(), "emailCode", deliveredCode(oldEmail)));
        assertEquals(oldEmail, vault.decrypt(player.getRecoveryEmailCipher()));
        JsonNode fresh = post("/email/change/new-code", token, Map.of("changeToken", authorized.path("changeToken").asText()));
        post("/email/change", token, Map.of("changeToken", authorized.path("changeToken").asText(),
                "challengeId", fresh.path("challengeId").asText(), "emailCode", deliveredCode(newEmail)));
        assertEquals(newEmail, vault.decrypt(player.getRecoveryEmailCipher()));
        assertEquals(2L, player.getRecoveryEmailVersion()); assertEquals(1L, player.getAuthVersion());
    }

    @Test void passwordRecoveryDoesNotCancelPendingDeletion() throws Exception {
        String email = "isolated-deletion@qq.com";
        Player player = oldAccount("email-pending-deletion", email);
        accounts.disableAccount(player.getId(), "original-password", "确认注销", "isolated-deletion-request");
        long deadline = player.getRecoverUntil();
        JsonNode sent = post("/password/reset-code", null, Map.of("serverId", "jiangsu-1", "username", player.getUsername(), "email", email));
        post("/password/reset", null, Map.of("serverId", "jiangsu-1", "username", player.getUsername(), "email", email,
                "challengeId", sent.path("challengeId").asText(), "emailCode", deliveredCode(email), "newPassword", "changed-password"));
        assertEquals("PENDING_DELETION", player.getAccountStatus()); assertEquals(deadline, player.getRecoverUntil());
        JsonNode login = post("/login", null, Map.of("serverId", "jiangsu-1", "username", player.getUsername(), "password", "changed-password"));
        assertEquals("RECOVERY_REQUIRED", login.path("status").asText()); assertFalse(login.has("token"));
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void validationFailurePersistsAttemptWithoutCreatingAnAccount() {
        String name = "isolated-attempt-record";
        Map<String, Object> fields = registrationFields(name, "initial-password");
        assertThrows(com.wargame.security.AccountException.class, () -> accountAuth.register(name, "initial-password",
                com.wargame.service.AuthService.CURRENT_REGISTRATION_AGREEMENT_VERSION, (String) fields.get("email"),
                (String) fields.get("challengeId"), "invalid", new org.springframework.mock.web.MockHttpServletRequest()));
        Integer attempts = new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(s ->
                em.createQuery("select c.failures from AccountEmailChallenge c where c.challengeId = :id", Integer.class)
                        .setParameter("id", fields.get("challengeId")).getSingleResult());
        assertEquals(1, attempts); assertFalse(playerRepository.existsByUsername(name));
    }

    @Test void transientDeliveryFailureRetainsEncryptedPayloadForTheSameCode() {
        String email = "isolated-delivery@qq.com";
        Map<String, Object> sent = accountEmails.registrationCode("isolated-delivery", email, "isolated-mail-delivery");
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("isolated test provider unavailable"))
                .doAnswer(call -> { testEmailBodies.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(accountMailSender).send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        emailDelivery.deliverPending();
        var queued = em.createQuery("select o from AccountEmailOutbox o where o.challengeId = :id",
                        com.wargame.model.entity.AccountEmailRecords.Outbox.class)
                .setParameter("id", sent.get("challengeId")).getSingleResult();
        assertEquals(1, queued.getAttempts()); assertNotNull(queued.getBodyCipher());
        String expectedBody = vault.decrypt(queued.getBodyCipher());
        queued.setNextAttemptAt(System.currentTimeMillis());
        emailDelivery.deliverPending();
        assertEquals(expectedBody, testEmailBodies.get(email)); assertEquals(2, queued.getAttempts());
        assertNull(queued.getBodyCipher()); assertNull(queued.getEmailCipher());
    }
}
