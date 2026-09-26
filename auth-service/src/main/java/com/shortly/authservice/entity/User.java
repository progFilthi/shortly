package com.shortly.authservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "users", indexes = {
        // Login resolves by username OR email on every attempt, so both are indexed. The
        // unique constraint is the authoritative guard; these make the lookup itself cheap.
        @Index(name = "idx_users_username", columnList = "username"),
        @Index(name = "idx_users_email", columnList = "email")
})
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36)
    private String id;

    @Column(unique = true, nullable = false, length = 64)
    private String username;

    @Column(unique = true, nullable = false, length = 320)
    private String email;

    /**
     * BCrypt hash, never the password. 60 characters at the default cost of 12, which is why
     * the column is sized rather than left to the 255 default.
     */
    @Column(nullable = false, length = 100)
    private String password;

    @Column(length = 1024)
    private String profilePictureUrl;

    @Column(length = 500)
    private String bio;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /* ------------------------------ sign-in hardening ----------------------------- */

    /**
     * Consecutive failed sign-ins, reset on any success.
     * <p>
     * Stored on the account rather than in a rate limiter keyed by IP, because brute-force
     * attempts against one account come from many IPs and a per-IP limiter misses that entirely.
     */
    @Column(nullable = false)
    private int failedLoginAttempts = 0;

    /**
     * While set and in the future, every sign-in is refused regardless of the password.
     * <p>
     * Cleared lazily on the next attempt rather than by a scheduled job: a sweeper would have to
     * run often enough to be timely, and the field is only ever read when it already blocks.
     */
    private Instant lockedUntil;

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /**
     * Records a failed sign-in, locking the account once the threshold is reached.
     * <p>
     * The current time is a parameter rather than read from {@code Instant.now()}. The entity
     * otherwise has two sources of time, and a lockout stamped with a different clock than the
     * one that evaluates it is a bug that only shows up under a skewed or injected clock.
     */
    public void recordFailedLogin(int maxAttempts, java.time.Duration lockoutDuration, Instant now) {
        this.failedLoginAttempts++;
        if (failedLoginAttempts >= maxAttempts) {
            this.lockedUntil = now.plus(lockoutDuration);
        }
    }

    public void recordSuccessfulLogin() {
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }
}
