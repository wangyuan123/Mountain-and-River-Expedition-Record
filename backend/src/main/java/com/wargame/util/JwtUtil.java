package com.wargame.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    /** Minimum 32 bytes (256 bits) required for HS256. */
    private static final int MIN_SECRET_BYTES = 32;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    /** Revoked tokens (logout / account compromised). Keyed by token id (jti). */
    private final ConcurrentMap<String, Long> revokedJtis = new ConcurrentHashMap<>();

    @PostConstruct
    void validateSecret() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes (got "
                + (secret == null ? 0 : secret.getBytes(StandardCharsets.UTF_8).length)
                + "). Set WARGAME_JWT_SECRET env var to a random string.");
        }
        if (secret.startsWith("dev-only")) {
            log.warn("[SECURITY] jwt.secret is using the dev fallback. Override WARGAME_JWT_SECRET in production!");
        }
    }

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String username, Long playerId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);
        return Jwts.builder()
                .subject(username)
                .claim("playerId", playerId)
                .id(java.util.UUID.randomUUID().toString())  // jti for revocation
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getKey())
                .compact();
    }

    /** Parse claims or throw. Callers should catch JwtException. */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            Claims claims = parseClaims(token);
            String jti = claims.getId();
            if (jti != null && revokedJtis.containsKey(jti)) {
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    public Long getPlayerIdFromToken(String token) {
        return parseClaims(token).get("playerId", Long.class);
    }

    /** Revoke a token (logout / forced sign-out). */
    public void revoke(String token) {
        try {
            Claims claims = parseClaims(token);
            String jti = claims.getId();
            if (jti != null) {
                revokedJtis.put(jti, claims.getExpiration().getTime());
                gc();
            }
        } catch (Exception ignore) {
            // already invalid, nothing to revoke
        }
    }

    /** Remove expired entries so the map doesn't grow unbounded. */
    private void gc() {
        long now = System.currentTimeMillis();
        revokedJtis.entrySet().removeIf(e -> e.getValue() < now);
    }
}
