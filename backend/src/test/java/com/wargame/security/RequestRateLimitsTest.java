package com.wargame.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wargame.model.AdminPrincipal;
import com.wargame.model.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RequestRateLimitsTest {
    private final AtomicLong now = new AtomicLong(1000);
    private final RequestLimitProperties config = new RequestLimitProperties();
    private final RequestRateLimits limits = new RequestRateLimits(new RateLimiter(100, now::get), new ClientIpResolver("10.0.0.2"), config, new ObjectMapper());

    private MockHttpServletRequest request(String method, String path, String remote) {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remote);
        return request;
    }

    @Test void urlsAndSpoofedAddressesShareOneIpQuotaAndRetryResetsOnlyAfterWindow() throws Exception {
        config.setIpPerMinute(2);
        for (String path : new String[]{"/api/auth/server", "/api/forum/hot"})
            assertTrue(limits.allowIp(request("GET", path, "192.0.2.1"), new MockHttpServletResponse()));
        var spoof = request("GET", "/api/forum/topics", "192.0.2.1");
        spoof.addHeader("X-Forwarded-For", "198.51.100.1");
        var rejected = new MockHttpServletResponse();
        assertFalse(limits.allowIp(spoof, rejected));
        assertEquals(429, rejected.getStatus());
        assertEquals("60", rejected.getHeader("Retry-After"));
        assertTrue(rejected.getContentAsString().contains("RATE_LIMITED"));
        assertTrue(rejected.getContentAsString().contains("\"retryAfter\":60"));
        now.addAndGet(60_000);
        assertTrue(limits.allowIp(spoof, new MockHttpServletResponse()));
    }

    @Test void trustedProxySeparatesClientsAndDoesNotUseTheirSpoofedPrefix() throws Exception {
        config.setIpPerMinute(1);
        var first = request("GET", "/api/auth/server", "10.0.0.2");
        first.addHeader("X-Forwarded-For", "203.0.113.1,192.0.2.1");
        assertTrue(limits.allowIp(first, new MockHttpServletResponse()));
        first.removeHeader("X-Forwarded-For");
        first.addHeader("X-Forwarded-For", "203.0.113.2,192.0.2.1");
        assertFalse(limits.allowIp(first, new MockHttpServletResponse()));
        var second = request("GET", "/api/auth/server", "10.0.0.2");
        second.addHeader("X-Forwarded-For", "192.0.2.2");
        assertTrue(limits.allowIp(second, new MockHttpServletResponse()));
    }

    @Test void writeQuotaIsSharedAcrossMethodsWhileReadsRemainAvailable() throws Exception {
        config.setIpWritesPerMinute(2);
        assertTrue(limits.allowIp(request("POST", "/api/shop/buy", "192.0.2.1"), new MockHttpServletResponse()));
        assertTrue(limits.allowIp(request("PUT", "/api/city/rename", "192.0.2.1"), new MockHttpServletResponse()));
        assertFalse(limits.allowIp(request("DELETE", "/api/anything", "192.0.2.1"), new MockHttpServletResponse()));
        assertTrue(limits.allowIp(request("GET", "/api/auth/me", "192.0.2.1"), new MockHttpServletResponse()));
    }

    @Test void accountsShareQuotaAcrossIpsTokensAndPathsAndAdminIdsDoNotCollideWithPlayers() throws Exception {
        config.setAccountPerMinute(2);
        var player = new UserPrincipal(1L, "player", "", false);
        var auth = new UsernamePasswordAuthenticationToken(player, null, player.getAuthorities());
        assertTrue(limits.allowAccount(request("GET", "/api/auth/me", "192.0.2.1"), new MockHttpServletResponse(), auth));
        var secondToken = new UsernamePasswordAuthenticationToken(player, null, player.getAuthorities());
        assertTrue(limits.allowAccount(request("GET", "/api/forum/topics", "192.0.2.2"), new MockHttpServletResponse(), secondToken));
        assertFalse(limits.allowAccount(request("GET", "/api/game/state", "192.0.2.3"), new MockHttpServletResponse(), auth));
        var admin = new AdminPrincipal(1L, "admin", "", "ADMIN", false);
        assertTrue(limits.allowAccount(request("GET", "/api/admin/auth/me", "192.0.2.1"), new MockHttpServletResponse(),
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities())));
    }

    @Test void accountWriteBudgetDoesNotBlockReadsAndWebsocketsHaveTheirOwnBudget() throws Exception {
        config.setAccountWritesPerMinute(1);
        config.setWebsocketPerIpPerMinute(1);
        var player = new UserPrincipal(1L, "player", "", false);
        var auth = new UsernamePasswordAuthenticationToken(player, null, player.getAuthorities());
        assertTrue(limits.allowAccount(request("POST", "/api/shop/buy", "192.0.2.1"), new MockHttpServletResponse(), auth));
        assertFalse(limits.allowAccount(request("POST", "/api/army/train", "192.0.2.2"), new MockHttpServletResponse(), auth));
        assertTrue(limits.allowAccount(request("GET", "/api/game/state", "192.0.2.2"), new MockHttpServletResponse(), auth));
        assertTrue(limits.allowIp(request("GET", "/ws/game", "192.0.2.1"), new MockHttpServletResponse()));
        assertFalse(limits.allowIp(request("GET", "/ws/game", "192.0.2.1"), new MockHttpServletResponse()));
        assertTrue(limits.allowIp(request("GET", "/api/auth/server", "192.0.2.1"), new MockHttpServletResponse()));
    }

    @Test void preflightsAndStaticAssetsNeverConsumeApiBudget() throws Exception {
        config.setIpPerMinute(1);
        for (int i = 0; i < 3; i++) {
            assertTrue(limits.allowIp(request("OPTIONS", "/api/shop/buy", "192.0.2.1"), new MockHttpServletResponse()));
            assertTrue(limits.allowIp(request("GET", "/index.html", "192.0.2.1"), new MockHttpServletResponse()));
        }
        assertTrue(limits.allowIp(request("GET", "/api/auth/server", "192.0.2.1"), new MockHttpServletResponse()));
        assertFalse(limits.allowIp(request("GET", "/api/auth/server", "192.0.2.1"), new MockHttpServletResponse()));
    }

    @Test void adminAccountLimitsCannotBeBypassedWithCaseAndRecoverAfterQuarterHour() {
        config.setAdminLoginPerAccountPerQuarterHour(2);
        limits.checkAdminLogin("Admin");
        limits.checkAdminLogin(" admin ");
        var failure = assertThrows(AccountException.class, () -> limits.checkAdminLogin("ADMIN"));
        assertEquals("RATE_LIMITED", failure.getCode());
        assertEquals(900, failure.getRetryAfter());
        now.addAndGet(900_000);
        assertDoesNotThrow(() -> limits.checkAdminLogin("admin"));
    }
}
