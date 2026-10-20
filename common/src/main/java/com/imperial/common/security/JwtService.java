package com.imperial.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and validates the platform's JWTs. All 4 services load this same
 * bean from the common module and must be configured with the same
 * jwt.secret (an env var in practice) - the token is only trustworthy
 * downstream because every service verifies it against the same key that
 * Tenant & Catalog Service signed it with. There is no separate identity
 * provider at this scale (per the plan's tech stack decision); this class
 * *is* the identity provider.
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long expirationMillis;

    public JwtService(
            @Value("${jwt.secret:phase1-dev-only-shared-signing-secret-CHANGE-ME-before-prod}") String secret,
            @Value("${jwt.expiration-minutes:60}") long expirationMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMillis = expirationMinutes * 60_000L;
    }

    public String generateToken(UUID tenantId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(tenantId.toString())
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMillis)))
                .signWith(key)
                .compact();
    }

    /** Empty on any invalid/expired/tampered token - callers treat that as "not authenticated", never as an error. */
    public Optional<ParsedToken> parseToken(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            UUID tenantId = UUID.fromString(claims.getSubject());
            String role = claims.get("role", String.class);
            return Optional.of(new ParsedToken(tenantId, role));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record ParsedToken(UUID tenantId, String role) {
    }
}
