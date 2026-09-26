package com.shortly.authservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A refresh token, stored by hash.
 *
 * <p>{@code familyId} groups every token descended from one sign-in. Rotation issues a new token
 * in the same family; reuse detection revokes the family. That grouping is what makes a stolen
 * token detectable at all - without it, "this token was already used" identifies a token but not
 * the rest of the session to kill.
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_tokens_hash", columnList = "tokenHash", unique = true),
        @Index(name = "idx_refresh_tokens_family", columnList = "familyId"),
        @Index(name = "idx_refresh_tokens_user", columnList = "userId"),
        @Index(name = "idx_refresh_tokens_expires", columnList = "expiresAt")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 64)
    private String userId;

    /** SHA-256 hex of the token. The token itself is never stored. */
    @Column(nullable = false, updatable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false, updatable = false)
    private UUID familyId;

    @Column(nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    /** Set when this token is exchanged. Non-null means consumed. */
    private Instant consumedAt;

    /** Set on logout, reuse detection, or "log out everywhere". */
    private Instant revokedAt;

    /** Best-effort client context, for a "where am I signed in" screen and for abuse triage. */
    @Column(length = 255)
    private String userAgent;

    @Column(length = 45)
    private String ipAddress;

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return now.isAfter(expiresAt);
    }

    /**
     * A token is live only if it has been neither consumed nor revoked nor expired. Used by the
     * "log out everywhere" bulk revoke so it does not need to load and filter every row.
     */
    public boolean isActiveAt(Instant now) {
        return !isConsumed() && !isRevoked() && !isExpiredAt(now);
    }

    public void consume(Instant when) {
        this.consumedAt = when;
    }

    public void revoke(Instant when) {
        this.revokedAt = when;
    }
}
