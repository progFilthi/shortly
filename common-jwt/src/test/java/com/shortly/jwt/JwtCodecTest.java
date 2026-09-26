package com.shortly.jwt;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared access-token codec.
 *
 * <p>This is the one piece of authentication logic the gateway and the auth service both depend
 * on, so it is tested on its own rather than only through a service. Every case here is a way a
 * token could be accepted when it should not be.
 */
class JwtCodecTest {

    /** 48 random-ish bytes, Base64. Comfortably over the 32-byte minimum for HS256. */
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef0123456789abcdef".getBytes());
    private static final String OTHER_SECRET = Base64.getEncoder().encodeToString(
            "ffffffffffffffffffffffffffffffffffffffffffffffff".getBytes());
    private static final String ISSUER = "shortly-test";
    private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    /* ------------------------------ happy path --------------------------------- */

    @Test
    void roundTripsAFreshlyIssuedToken() {
        JwtCodec codec = new JwtCodec(SECRET, ISSUER, clockAt(NOW));

        String token = codec.issueAccessToken("user-1", "tester", "tester@example.com",
                Duration.ofMinutes(15));
        var verification = codec.verify(token);

        assertThat(verification.succeeded()).isTrue();
        assertThat(verification.claims().userId()).isEqualTo("user-1");
        assertThat(verification.claims().username()).isEqualTo("tester");
        assertThat(verification.claims().email()).isEqualTo("tester@example.com");
    }

    @Test
    void issuesTokensWithAUniqueIdEvenWithinTheSameSecond() {
        JwtCodec codec = new JwtCodec(SECRET, ISSUER, clockAt(NOW));

        var first = codec.verify(codec.issueAccessToken("user-1", "a", "a@example.com",
                Duration.ofMinutes(15))).claims();
        var second = codec.verify(codec.issueAccessToken("user-1", "a", "a@example.com",
                Duration.ofMinutes(15))).claims();

        // A frozen clock means identical iat/exp, so only jti distinguishes them. Useful when
        // tracing one specific session.
        assertThat(first.tokenId()).isNotBlank().isNotEqualTo(second.tokenId());
    }

    /* -------------------------------- expiry ----------------------------------- */

    @Test
    void reportsAnExpiredTokenSeparatelyFromAnInvalidOne() {
        // The distinction is the whole reason verify() returns a result instead of a boolean:
        // "expired" means refresh and retry, "invalid" means sign in again.
        MutableClock clock = new MutableClock(NOW);
        JwtCodec codec = new JwtCodec(SECRET, ISSUER, clock);
        String token = codec.issueAccessToken("user-1", "tester", "tester@example.com",
                Duration.ofMinutes(15));

        assertThat(codec.verify(token).succeeded()).isTrue();

        clock.advance(Duration.ofMinutes(16));

        var verification = codec.verify(token);
        assertThat(verification.succeeded()).isFalse();
        assertThat(verification.failure()).isEqualTo(JwtCodec.FailureReason.EXPIRED);
    }

    /* --------------------------------- forgery --------------------------------- */

    @Test
    void rejectsATokenSignedWithADifferentKey() {
        String token = new JwtCodec(SECRET, ISSUER, clockAt(NOW))
                .issueAccessToken("user-1", "tester", "tester@example.com", Duration.ofMinutes(15));

        var verification = new JwtCodec(OTHER_SECRET, ISSUER, clockAt(NOW)).verify(token);

        assertThat(verification.succeeded()).isFalse();
        assertThat(verification.failure()).isEqualTo(JwtCodec.FailureReason.INVALID);
    }

    @Test
    void rejectsATokenFromADifferentIssuer() {
        // Same signing key, different environment. Without pinning the issuer, a staging token
        // would be accepted in production.
        String token = new JwtCodec(SECRET, "shortly-staging", clockAt(NOW))
                .issueAccessToken("user-1", "tester", "tester@example.com", Duration.ofMinutes(15));

        var verification = new JwtCodec(SECRET, ISSUER, clockAt(NOW)).verify(token);

        assertThat(verification.succeeded()).isFalse();
    }

    @Test
    void rejectsGarbage() {
        JwtCodec codec = new JwtCodec(SECRET, ISSUER, clockAt(NOW));

        assertThat(codec.verify("not-a-token").succeeded()).isFalse();
        assertThat(codec.verify("").succeeded()).isFalse();
        assertThat(codec.verify(null).succeeded()).isFalse();
    }

    @Test
    void rejectsATamperedPayload() {
        JwtCodec codec = new JwtCodec(SECRET, ISSUER, clockAt(NOW));
        String token = codec.issueAccessToken("user-1", "tester", "tester@example.com",
                Duration.ofMinutes(15));

        // Flip a character in the payload segment. The signature must no longer match.
        String[] parts = token.split("\\.");
        String tamperedPayload = parts[1].substring(0, parts[1].length() - 2)
                + (parts[1].endsWith("A") ? "B" : "A");

        assertThat(codec.verify(parts[0] + "." + tamperedPayload + "." + parts[2]).succeeded())
                .as("a modified payload must not verify")
                .isFalse();
    }

    @Test
    void rejectsAnUnsignedTokenWithAnAlgNoneHeader() {
        // The classic JWT bypass. jjwt rejects "none" outright, which is exactly why the codec
        // pins the algorithm by verifying with a symmetric key rather than trusting the header.
        JwtCodec codec = new JwtCodec(SECRET, ISSUER, clockAt(NOW));

        String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"user-1\",\"iss\":\"" + ISSUER + "\",\"typ\":\"access\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(codec.verify(header + "." + payload + ".").succeeded()).isFalse();
    }

    /* ----------------------------- configuration ------------------------------- */

    @Test
    void refusesToStartWithoutASecret() {
        // Failing at construction rather than at first use: a service that boots but cannot
        // verify tokens fails somewhere far less obvious.
        assertThatThrownBy(() -> new JwtCodec("", ISSUER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not set");
    }

    @Test
    void refusesASecretThatIsTooShortForHs256() {
        // 16 bytes. Under the 32-byte floor for HS256, which is a real deployment mistake rather
        // than a hypothetical one: `openssl rand -base64 16` is an easy thing to reach for.
        String tooShort = Base64.getEncoder().encodeToString(
                "sixteen-byte-key".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new JwtCodec(tooShort, ISSUER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    void refusesASecretThatIsNotBase64() {
        assertThatThrownBy(() -> new JwtCodec("not base64 !!!", ISSUER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }

    /** Advanceable clock, so expiry is reachable without sleeping. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advance(Duration amount) {
            now = now.plus(amount);
        }
    }
}
