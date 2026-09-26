package com.shortly.authservice;

import com.shortly.authservice.config.AuthProperties;
import com.shortly.authservice.service.RefreshTokenRevocationService;
import com.shortly.authservice.entity.User;
import com.shortly.authservice.jwt.JwtTokenProvider;
import com.shortly.authservice.mapper.UserMapper;
import com.shortly.authservice.repository.RefreshTokenRepository;
import com.shortly.authservice.repository.UserRepository;
import com.shortly.authservice.service.AuthService;
import com.shortly.authservice.service.LoginAttemptService;
import com.shortly.authservice.service.RefreshTokenService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Shared fixtures for auth-service tests.
 *
 * <p>Two things every test here needs and neither should assemble itself: a full
 * {@link AuthProperties} with fast values, and a clock the test can move. Token expiry and account
 * lockouts are both time-dependent, and asserting them with a real clock means either sleeping or
 * writing flaky tests.
 */
public final class TestFixtures {

    public static final String PASSWORD = "correct-horse-battery";
    public static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

    /**
     * A mutable clock, so a test can advance past an expiry without waiting.
     */
    public static final class MutableClock extends Clock {

        private Instant now = NOW;

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

        public void advance(Duration amount) {
            now = now.plus(amount);
        }
    }

    private TestFixtures() {
    }

    /**
     * Test-tuned properties: a cheap BCrypt cost so hashing does not dominate the run, and short
     * windows so expiry is reachable in one assertion.
     */
    public static AuthProperties properties() {
        return new AuthProperties(
                Duration.ofMinutes(15),        // accessTokenTtl
                Duration.ofDays(30),          // refreshTokenTtl
                Duration.ofSeconds(20),        // refreshReuseGrace
                3,                            // maxFailedAttempts
                Duration.ofMinutes(15),        // lockoutDuration
                4,                            // bcryptCost - minimum BCrypt accepts
                10,                           // minPasswordLength
                128,                          // maxPasswordLength
                "test-gateway-secret",        // gatewaySecret
                false,                        // requireGatewaySecret
                "shortly-test",               // issuer
                "dGVzdC1vbmx5LXNpZ25pbmcta2V5LWZvci11bml0LXRlc3RzLW1hdC1iZS1sb25n");
    }

    public static PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(4);
    }

    public static JwtTokenProvider tokenProvider(AuthProperties properties, Clock clock) {
        // The production constructor is package-private on purpose; this is the test seam.
        return new JwtTokenProvider(properties, clock);
    }

    /** Uses the clock-injecting constructor, which is why the seam is public. */
    public static RefreshTokenService refreshTokenService(RefreshTokenRepository repository,
                                                          AuthProperties properties,
                                                          Clock clock) {
        return new RefreshTokenService(
                repository, new RefreshTokenRevocationService(repository), properties, clock);
    }

    public static void stubUserSave(UserRepository users) {
        when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId("user-1");
            }
            return saved;
        });
    }

    public static AuthService authService(UserRepository users,
                                   RefreshTokenRepository tokens,
                                   AuthProperties properties,
                                   Clock clock) {
        return new AuthService(users, tokens, passwordEncoder(),
                tokenProvider(properties, clock),
                refreshTokenService(tokens, properties, clock),
                new UserMapper(), properties,
                loginAttempts(users, properties, clock),
                clock);
    }

    /**
     * A real LoginAttemptService, not a mock. The lockout tests are about whether the failure
     * counter commits independently of the exception that accompanies it, and a mocked
     * collaborator would stub that behaviour away - which is precisely the bug it exists to
     * prevent.
     */
    public static LoginAttemptService loginAttempts(UserRepository users,
                                                    AuthProperties properties,
                                                    Clock clock) {
        return new LoginAttemptService(users, properties, clock);
    }

    public static User user(String id, String username, String email, String rawPassword) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder().encode(rawPassword));
        return user;
    }
}
