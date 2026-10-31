package com.wargame.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class ClientIpResolverTest {
    private MockHttpServletRequest request(String remote, String forwarded) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        if (forwarded != null) request.addHeader("X-Forwarded-For", forwarded);
        return request;
    }

    @Test void directClientsCannotChooseTheirRateLimitOrAuditAddress() {
        var resolver = new ClientIpResolver("10.0.0.2,2001:db8:1::/64");
        var request = request("198.51.100.5", "203.0.113.9,10.0.0.2");
        request.addHeader("X-Real-IP", "203.0.113.8");
        request.addHeader("Forwarded", "for=203.0.113.7");
        assertEquals("198.51.100.5", resolver.resolve(request));
        assertEquals("10.0.0.2", new ClientIpResolver("").resolve(request("10.0.0.2", "203.0.113.9")));
    }

    @Test void trustStopsAtTheFirstUntrustedHopInsteadOfUsingTheSpoofedLeftmostAddress() {
        var resolver = new ClientIpResolver("10.0.0.0/24");
        assertEquals("198.51.100.5", resolver.resolve(request("10.0.0.2", "203.0.113.9,198.51.100.5,10.0.0.3")));
        assertEquals("198.51.100.5", resolver.resolve(request("10.0.0.2", "198.51.100.5")));
    }

    @Test void malformedAndRepeatedHeadersFailClosedToTheTcpPeer() {
        var resolver = new ClientIpResolver("10.0.0.2");
        for (String value : new String[]{"", "unknown", "localhost", "127.1", "001.2.3.4", "999.1.1.1", "198.51.100.5:80",
                "[2001:db8::1]", "2001:db8::1%eth0", "198.51.100.5,", ",198.51.100.5", "1.1.1.1,".repeat(17), "a".repeat(2049)}) {
            var request = request("10.0.0.2", value);
            request.addHeader("X-Real-IP", "203.0.113.1");
            assertEquals("10.0.0.2", resolver.resolve(request), value);
        }
        var repeated = request("10.0.0.2", "198.51.100.5");
        repeated.addHeader("X-Forwarded-For", "203.0.113.9");
        assertEquals("10.0.0.2", resolver.resolve(repeated));
    }

    @Test void ipv6AndMappedIpv4AreCanonicalizedAndLegacyRealIpStillRequiresTrust() {
        var resolver = new ClientIpResolver("2001:db8:1::/64,10.0.0.2");
        assertEquals("2001:db8:0:0:0:0:0:1", resolver.resolve(request("2001:db8:1::2", "2001:DB8::0001")));
        assertEquals("192.0.2.5", resolver.resolve(request("::ffff:192.0.2.5", "198.51.100.5")));
        var real = request("10.0.0.2", null);
        real.addHeader("X-Real-IP", "198.51.100.5");
        assertEquals("198.51.100.5", resolver.resolve(real));
        real.setRemoteAddr("10.0.0.3");
        assertEquals("10.0.0.3", resolver.resolve(real));
        assertEquals("unknown", resolver.resolve(null));
    }

    @Test void dangerousOrInvalidProxyConfigurationCannotStart() {
        for (String value : new String[]{"*", "localhost", "0.0.0.0/0", "::/0", "10.0.0.1/33", "::1/129", "10.0.0.1/abc", "10.0.0.1/1/2", "[::1]"})
            assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver(value), value);
    }
}
