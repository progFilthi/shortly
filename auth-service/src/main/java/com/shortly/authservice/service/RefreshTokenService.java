package com.shortly.authservice.service;

import com.shortly.authservice.config.AuthProperties;
import com.shortly.authservice.entity.RefreshToken;
import com.shortly.authservice.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** Issues, rotates, and revokes refresh tokens. Why opaque rather than JWT. A JWT refresh token
 * every refresh is exactly the database this design was trying to avoid. */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** 256 bits. Comfortably past the point of guessing, and a standard URL-safe token size. */
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final RefreshTokenRevocationService revocation;
    private final AuthProperties properties;
    private final Clock clock;

    /** Single constructor, taking the injected {@link Clock}. */
    public RefreshTokenService(RefreshTokenRepository repository,
                               RefreshTokenRevocationService revocation,
                               AuthProperties properties,
                               Clock clock) {
        this.repository = repository;
        this.revocation = revocation;
        this.properties = properties;
        this.clock = clock;
    }

    /** The token string to hand the client. */
    public record IssuedToken(String token, RefreshToken record) {
    }

    /** Issues a token in a new family - the root of a session. */
    @Transactional
    public IssuedToken issue(String userId, UUID familyId, String userAgent, String ipAddress) {
        String secret = generateSecret();
        Instant now = clock.instant();

        RefreshToken token = RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .tokenHash(hash(secret))
                // A null family means "this is the first token of a new session".
                .familyId(familyId == null ? UUID.randomUUID() : familyId)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(properties.refreshTokenTtlSeconds()))
                .userAgent(truncate(userAgent, 255))
                .ipAddress(truncate(ipAddress, 45))
                .build();

        return new IssuedToken(secret, repository.save(token));
    }

    /** Consumes a presented token and issues its replacement. */
    @Transactional
    public IssuedToken rotate(String presentedToken, String userAgent, String ipAddress) {
        String hash = hash(presentedToken);
        Optional<RefreshToken> found = repository.findByTokenHash(hash);

        if (found.isEmpty()) {
            // Either forged or already garbage-collected. Nothing to revoke, because a family cannot be
            // identified from a token we have never seen.
            throw new com.shortly.authservice.exceptions.RefreshTokenInvalidException();
        }

        RefreshToken token = found.get();
        Instant now = clock.instant();

        if (token.isRevoked()) {
            // A revoked token being presented is either a replay after logout, or an attacker using a token
            // the legitimate client already burned. Revoke the family either way.
            log.warn("Revoked refresh token presented for user {}; revoking family {}",
                    token.getUserId(), token.getFamilyId());
            revocation.revokeFamily(token.getFamilyId(), now);
            throw new com.shortly.authservice.exceptions.RefreshTokenInvalidException();
        }

        if (token.isConsumed()) {
            if (withinGraceWindow(token, now)) {
                // Almost certainly a client retry after a lost response.
                log.debug("Replayed refresh token for user {} inside the grace window; "
                        + "tolerating", token.getUserId());
                throw new com.shortly.authservice.exceptions.RefreshTokenInvalidException(
                        "replayed-within-grace");
            }

            // Outside the grace window this is genuine reuse: two parties hold the token.
            log.warn("Reuse of an already-consumed refresh token for user {}; revoking family {}",
                    token.getUserId(), token.getFamilyId());
            revocation.revokeFamily(token.getFamilyId(), now);
            throw new com.shortly.authservice.exceptions.RefreshTokenInvalidException("token-reuse");
        }

        if (token.isExpiredAt(now)) {
            throw new com.shortly.authservice.exceptions.RefreshTokenInvalidException("token-expired");
        }

        token.consume(now);
        repository.save(token);

        return issue(token.getUserId(), token.getFamilyId(), userAgent, ipAddress);
    }

    /** Revokes every live token in a family. */
    public int revokeFamily(UUID familyId) {
        return revocation.revokeFamily(familyId, clock.instant());
    }

    /** Revokes every live token for a user across all families - "log out everywhere". */
    public int revokeAllForUser(String userId) {
        return revocation.revokeAllForUser(userId, clock.instant());
    }

    /** Housekeeping. */
    @Transactional
    public int purgeExpired() {
        return repository.deleteExpiredBefore(clock.instant());
    }

    private boolean withinGraceWindow(RefreshToken token, Instant now) {
        if (token.getConsumedAt() == null) {
            return false;
        }
        Instant graceEnds = token.getConsumedAt().plusSeconds(properties.refreshReuseGrace().toSeconds());
        return now.isBefore(graceEnds);
    }

    private static String generateSecret() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 of the token, stored instead of the token itself. A database leak must not hand the
     * attacker working refresh tokens. */
    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
