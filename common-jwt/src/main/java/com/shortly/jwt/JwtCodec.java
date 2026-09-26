package com.shortly.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies access tokens.
 *
 * <p>One implementation, used by both the auth service that mints tokens and the gateway that
 * checks them. That matters: verification rules that exist in two places will eventually differ,
 * and a difference here is an authentication bypass rather than a bug.
 *
 * <p>Access tokens are JWTs because verification runs on every request and must not touch a
 * database. The cost of that statelessness is that a token cannot be revoked before it expires -
 * so the expiry <em>is</em> the revocation policy, and it is deliberately short.
 *
 * <p>Not a Spring bean. It is constructed by whichever service needs it, from that service's own
 * configuration, so it carries no framework dependency and is trivially testable.
 */
public final class JwtCodec {

    private static final Logger log = LoggerFactory.getLogger(JwtCodec.class);

    private final String issuer;
    private final SecretKey signingKey;
    private final Clock clock;

    public JwtCodec(String base64Secret, String issuer) {
        this(base64Secret, issuer, Clock.systemUTC());
    }

    /** Clock-injectable so expiry can be tested without sleeping. */
    public JwtCodec(String base64Secret, String issuer, Clock clock) {
        this.issuer = issuer;
        this.clock = clock;
        this.signingKey = decodeKey(base64Secret);
    }

    /** Verified identity carried by an access token. */
    public record AccessTokenClaims(String userId, String username, String email, String tokenId) {
    }

    /** Why a verification failed. Lets a caller answer precisely instead of a blanket 401. */
    public enum FailureReason {
        /** Signature, issuer, or token type is wrong. */
        INVALID,
        /** Well-formed and correctly signed, but past its expiry. The client should refresh. */
        EXPIRED
    }

    public record Verification(AccessTokenClaims claims, FailureReason failure) {

        public boolean succeeded() {
            return claims != null;
        }

        public static Verification ok(AccessTokenClaims claims) {
            return new Verification(claims, null);
        }

        public static Verification failed(FailureReason reason) {
            return new Verification(null, reason);
        }
    }

    /**
     * Mints an access token.
     *
     * <p>Carries a unique {@code jti} so two tokens issued in the same second are still
     * distinguishable - useful when tracing a specific session.
     */
    public String issueAccessToken(String userId, String username, String email, Duration ttl) {
        Instant issuedAt = clock.instant();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(userId)
                .claim(JwtClaims.TOKEN_TYPE, JwtClaims.TYPE_ACCESS)
                .claim(JwtClaims.USERNAME, username)
                .claim(JwtClaims.EMAIL, email)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(ttl)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Verifies a token.
     * <p>
     * Returns a result rather than throwing, because the two failure modes need different
     * responses: an expired token means "refresh and retry", an invalid one means "sign in
     * again", and collapsing them leaves clients unable to recover on their own.
     */
    public Verification verify(String token) {
        if (token == null || token.isBlank()) {
            return Verification.failed(FailureReason.INVALID);
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    // Pinning the issuer means a token signed with the same key but intended for
                    // a different environment is rejected.
                    .requireIssuer(issuer)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (!JwtClaims.TYPE_ACCESS.equals(claims.get(JwtClaims.TOKEN_TYPE, String.class))) {
                log.warn("Rejected a token whose type is not '{}'", JwtClaims.TYPE_ACCESS);
                return Verification.failed(FailureReason.INVALID);
            }

            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                return Verification.failed(FailureReason.INVALID);
            }

            return Verification.ok(new AccessTokenClaims(
                    subject,
                    claims.get(JwtClaims.USERNAME, String.class),
                    claims.get(JwtClaims.EMAIL, String.class),
                    claims.getId()));
        } catch (ExpiredJwtException e) {
            return Verification.failed(FailureReason.EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Token rejected: {}", e.getMessage());
            return Verification.failed(FailureReason.INVALID);
        }
    }

    /** Convenience for callers that treat every failure identically. */
    public Optional<AccessTokenClaims> tryVerify(String token) {
        Verification verification = verify(token);
        return verification.succeeded()
                ? Optional.of(verification.claims())
                : Optional.empty();
    }

    /**
     * Decodes and validates the signing key.
     * <p>
     * Fails at construction rather than at first use. A missing or short secret is a deployment
     * mistake, and a service that starts but cannot mint or verify tokens fails somewhere far less
     * obvious. HS256 requires at least 256 bits of key material.
     */
    private static SecretKey decodeKey(String base64Secret) {
        if (base64Secret == null || base64Secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT signing secret is not set. It must be a Base64-encoded HMAC key of at "
                            + "least 32 bytes, identical across every service that verifies access "
                            + "tokens. Generate one with: openssl rand -base64 48");
        }
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(base64Secret);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "JWT signing secret is not valid Base64. Generate one with: "
                            + "openssl rand -base64 48", e);
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT signing secret decodes to " + keyBytes.length + " bytes; HS256 requires at "
                            + "least 32. Generate one with: openssl rand -base64 48");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
