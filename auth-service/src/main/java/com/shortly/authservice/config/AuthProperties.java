package com.shortly.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Authentication and token settings.
 *
 * <p>The access/refresh split is the important part. Access tokens are stateless JWTs and short,
 * because they cannot be revoked and a long-lived one is a long-lived liability if it leaks.
 * Refresh tokens are opaque, database-backed, long-lived, and rotated on every use, so a stolen
 * refresh token is detectable and revocable.
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(

        /* --------------------------------- access ---------------------------------- */

        /**
         * Access token lifetime.
         * <p>
         * The previous value was 24 hours, which is the gap between "stateless and cheap" and
         * "worth stealing". Short enough that logout and revocation feel immediate, long enough
         * that an active user never sees a refresh mid-scroll.
         */
        @DefaultValue("15m") Duration accessTokenTtl,

        /* --------------------------------- refresh --------------------------------- */

        @DefaultValue("30d") Duration refreshTokenTtl,

        /**
         * Grace window in which a just-rotated refresh token is still accepted.
         * <p>
         * Mobile clients retry. Without a grace window, a response lost in transit on a flaky
         * connection burns the only copy of the token and forces a full sign-in, which looks to
         * the user like being logged out at random. The window is short enough that it does not
         * meaningfully widen the replay window it exists to smooth over.
         */
        @DefaultValue("20s") Duration refreshReuseGrace,

        /* --------------------------------- sign-in --------------------------------- */

        /** Consecutive failures before the account locks. */
        @DefaultValue("5") int maxFailedAttempts,

        /** How long the lockout lasts once the threshold is reached. */
        @DefaultValue("15m") Duration lockoutDuration,

        /**
         * Cost factor for BCrypt. 12 is roughly 250ms per hash on current server hardware, which
         * is the usual target: high enough to make offline cracking expensive, low enough that a
         * burst of sign-ins does not exhaust the thread pool.
         */
        @DefaultValue("12") int bcryptCost,

        /* ------------------------------- passwords -------------------------------- */

        /**
         * Minimum password length.
         * <p>
         * Length, not composition rules. "Password!" is weaker than "correct horse battery
         * staple" while satisfying every character-class rule ever invented.
         */
        @DefaultValue("10") int minPasswordLength,

        @DefaultValue("128") int maxPasswordLength,

        /* --------------------------------- trust ----------------------------------- */

        /**
         * Shared secret proving a request arrived through the gateway.
         * <p>
         * Separate from the JWT signing key on purpose. Sharing one secret means a leaked signing
         * key also lets an attacker forge the gateway header and impersonate any user id.
         */
        @DefaultValue("") String gatewaySecret,

        /** Whether to enforce {@code X-Gateway-Secret}. Disable only for local testing. */
        @DefaultValue("true") boolean requireGatewaySecret,

        /* --------------------------------- issuer ---------------------------------- */

        @DefaultValue("shortly") String issuer,

        /**
         * Base64-encoded HMAC-SHA signing key for access tokens.
         * <p>
         * Kept separate from {@link #gatewaySecret()}. When one key signs tokens and also
         * authenticates the gateway, leaking it lets an attacker mint a session <em>and</em;
         * impersonate any user id to internal services.
         */
        @DefaultValue("") String signingSecret
) {

    public int accessTokenTtlSeconds() {
        return (int) accessTokenTtl.toSeconds();
    }

    public int refreshTokenTtlSeconds() {
        return (int) refreshTokenTtl.toSeconds();
    }
}
