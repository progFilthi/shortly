package com.shortly.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Authentication and token settings. */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(

        /* --------------------------------- access ---------------------------------- */

        /** Access token lifetime. */
        @DefaultValue("15m") Duration accessTokenTtl,

        /* --------------------------------- refresh --------------------------------- */

        @DefaultValue("30d") Duration refreshTokenTtl,

        /** Grace window in which a just-rotated refresh token is still accepted. */
        @DefaultValue("20s") Duration refreshReuseGrace,

        /* --------------------------------- sign-in --------------------------------- */

        /** Consecutive failures before the account locks. */
        @DefaultValue("5") int maxFailedAttempts,

        /** How long the lockout lasts once the threshold is reached. */
        @DefaultValue("15m") Duration lockoutDuration,

        /** Cost factor for BCrypt. 12 is roughly 250ms per hash on current server hardware, which is the
         * ins does not exhaust the thread pool. */
        @DefaultValue("12") int bcryptCost,

        /* ------------------------------- passwords -------------------------------- */

        /** Minimum password length. Length, not composition rules. "Password!" is weaker than "correct
         * horse battery staple" while satisfying every character-class rule ever invented. */
        @DefaultValue("10") int minPasswordLength,

        @DefaultValue("128") int maxPasswordLength,

        /* --------------------------------- trust ----------------------------------- */

        /** Shared secret proving a request arrived through the gateway. */
        @DefaultValue("") String gatewaySecret,

        /** Whether to enforce {@code X-Gateway-Secret}. Disable only for local testing. */
        @DefaultValue("true") boolean requireGatewaySecret,

        /* --------------------------------- issuer ---------------------------------- */

        @DefaultValue("shortly") String issuer,

        /** Base64-encoded HMAC-SHA signing key for access tokens. */
        @DefaultValue("") String signingSecret
) {

    public int accessTokenTtlSeconds() {
        return (int) accessTokenTtl.toSeconds();
    }

    public int refreshTokenTtlSeconds() {
        return (int) refreshTokenTtl.toSeconds();
    }
}
