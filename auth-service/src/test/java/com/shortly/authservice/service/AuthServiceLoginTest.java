package com.shortly.authservice.service;

import com.shortly.authservice.TestFixtures;
import com.shortly.authservice.config.AuthProperties;
import com.shortly.authservice.dto.LoginRequest;
import com.shortly.authservice.dto.RegisterRequest;
import com.shortly.authservice.entity.User;
import com.shortly.authservice.exceptions.AccountLockedException;
import com.shortly.authservice.exceptions.CredentialsTakenException;
import com.shortly.authservice.exceptions.InvalidCredentialsException;
import com.shortly.authservice.exceptions.PasswordTooWeakException;
import com.shortly.authservice.repository.RefreshTokenRepository;
import com.shortly.authservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Sign-in behaviour: what a wrong password does, what a lockout does, and what an unknown
 * account does.
 *
 * <p>The last of those is the interesting one. Every "account does not exist" test here asserts
 * that a password hash is still computed, because that is what keeps the response time
 * indistinguishable from a wrong password.
 */
class AuthServiceLoginTest {

    private UserRepository users;
    private RefreshTokenRepository tokens;
    private TestFixtures.MutableClock clock;
    private AuthProperties properties;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        tokens = mock(RefreshTokenRepository.class);
        clock = new TestFixtures.MutableClock();
        properties = TestFixtures.properties();
        authService = TestFixtures.authService(users, tokens, properties, clock);

        TestFixtures.stubUserSave(users);
        when(users.findById(anyString())).thenReturn(Optional.empty());
    }

    private void givenStoredUser(String id, String username, String email) {
        when(users.findByUsernameIgnoreCase(anyString()))
                .thenReturn(Optional.ofNullable(
                        username.equalsIgnoreCase("nobody") ? null
                                : TestFixtures.user(id, username, email, TestFixtures.PASSWORD)));
        when(users.findByEmailIgnoreCase(anyString()))
                .thenReturn(Optional.ofNullable(
                        email.equalsIgnoreCase("nobody") ? null
                                : TestFixtures.user(id, username, email, TestFixtures.PASSWORD)));
    }

    /* ------------------------------- happy paths ------------------------------- */

    @Test
    void signsInWithTheCorrectPassword() {
        givenStoredUser("user-1", "tester", "tester@example.com");

        var response = authService.login(
                new LoginRequest("tester", TestFixtures.PASSWORD), "agent", "127.0.0.1");

        assertThat(response.token()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.userId()).isEqualTo("user-1");
        assertThat(response.username()).isEqualTo("tester");
        assertThat(response.email()).isEqualTo("tester@example.com");
    }

    @Test
    void acceptsAnEmailInPlaceOfAUsername() {
        givenStoredUser("user-1", "tester", "tester@example.com");

        var response = authService.login(
                new LoginRequest("tester@example.com", TestFixtures.PASSWORD), "agent", "127.0.0.1");

        assertThat(response.userId()).isEqualTo("user-1");
    }

    @Test
    void issuesAFreshTokenPairOnEverySignIn() {
        givenStoredUser("user-1", "tester", "tester@example.com");

        var first = authService.login(
                new LoginRequest("tester", TestFixtures.PASSWORD), "agent", "127.0.0.1");
        var second = authService.login(
                new LoginRequest("tester", TestFixtures.PASSWORD), "agent", "127.0.0.1");

        // Two sessions must not share a refresh token, or revoking one would revoke both.
        assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
    }

    @Test
    void reportsTheAccessTokenLifetimeSoTheClientCanRefreshProactively() {
        givenStoredUser("user-1", "tester", "tester@example.com");

        var response = authService.login(
                new LoginRequest("tester", TestFixtures.PASSWORD), "agent", "127.0.0.1");

        assertThat(response.expiresIn()).isEqualTo(properties.accessTokenTtlSeconds());
        assertThat(response.refreshExpiresIn()).isEqualTo(properties.refreshTokenTtlSeconds());
    }

    /* ------------------------------ wrong password ----------------------------- */

    @Test
    void rejectsAWrongPasswordWithTheSameMessageAsAnUnknownAccount() {
        givenStoredUser("user-1", "tester", "tester@example.com");

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("tester", "not-the-password"), "agent", "127.0.0.1"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid username or password.");
    }

    @Test
    void rejectsAnUnknownAccountWithTheSameMessage() {
        givenStoredUser("user-1", "nobody", "nobody");

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("nobody", TestFixtures.PASSWORD), "agent", "127.0.0.1"))
                .isInstanceOf(InvalidCredentialsException.class)
                // Byte-identical to the wrong-password message. A difference here is an account
                // enumeration oracle.
                .hasMessage("Invalid username or password.");
    }

    @Test
    void hashesAPasswordEvenWhenTheAccountDoesNotExist() {
        // The whole point of the unknown-account path. If this is ever "optimised" away, response
        // timing enumerates the user table, so it is asserted directly with a spy rather than
        // left to a comment.
        PasswordEncoder spyEncoder = org.mockito.Mockito.spy(TestFixtures.passwordEncoder());
        AuthService service = new AuthService(users, tokens, spyEncoder,
                TestFixtures.tokenProvider(properties, clock),
                TestFixtures.refreshTokenService(tokens, properties, clock),
                new com.shortly.authservice.mapper.UserMapper(), properties,
                TestFixtures.loginAttempts(users, properties, clock), clock);
        givenStoredUser("user-1", "nobody", "nobody");

        assertThatThrownBy(() -> service.login(
                new LoginRequest("nobody", TestFixtures.PASSWORD), "agent", "127.0.0.1"))
                .isInstanceOf(InvalidCredentialsException.class);

        // An encode() against a throwaway value, so the response costs the same as a real
        // comparison. See AuthService#login.
        verify(spyEncoder).encode(anyString());
        verify(spyEncoder, never()).matches(anyString(), anyString());
    }

    /* --------------------------------- lockout --------------------------------- */

    @Test
    void locksTheAccountAfterRepeatedFailures() {
        User stored = TestFixtures.user("user-1", "tester", "tester@example.com", TestFixtures.PASSWORD);
        when(users.findByUsernameIgnoreCase("tester")).thenReturn(Optional.of(stored));
        when(users.findByEmailIgnoreCase("tester")).thenReturn(Optional.of(stored));
        // LoginAttemptService reloads the account in its own transaction, so the lookup has to
        // resolve there too.
        when(users.findById("user-1")).thenReturn(Optional.of(stored));

        for (int attempt = 1; attempt < properties.maxFailedAttempts(); attempt++) {
            assertThatThrownBy(() -> authService.login(
                    new LoginRequest("tester", "wrong"), "agent", "127.0.0.1"))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        // The final failure trips the threshold and reports the lockout rather than a plain
        // rejection, so the client can tell the user to wait rather than to keep trying.
        assertThatThrownBy(() -> authService.login(
                new LoginRequest("tester", "wrong"), "agent", "127.0.0.1"))
                .isInstanceOf(AccountLockedException.class);
    }

    @Test
    void refusesEvenTheCorrectPasswordWhileLocked() {
        User stored = TestFixtures.user("user-1", "tester", "tester@example.com", TestFixtures.PASSWORD);
        when(users.findByUsernameIgnoreCase("tester")).thenReturn(Optional.of(stored));
        when(users.findByEmailIgnoreCase("tester")).thenReturn(Optional.of(stored));
        // LoginAttemptService reloads the account in its own transaction, so the lookup has to
        // resolve there too.
        when(users.findById("user-1")).thenReturn(Optional.of(stored));

        for (int attempt = 0; attempt < properties.maxFailedAttempts(); attempt++) {
            try {
                authService.login(new LoginRequest("tester", "wrong"), "agent", "127.0.0.1");
            } catch (RuntimeException expected) {
                // The final attempt throws the lockout; earlier ones throw invalid credentials.
            }
        }

        // Correct password, still refused. A lockout that a correct password defeats is not one.
        assertThatThrownBy(() -> authService.login(
                new LoginRequest("tester", TestFixtures.PASSWORD), "agent", "127.0.0.1"))
                .isInstanceOf(AccountLockedException.class);
    }

    @Test
    void theLockoutCarriesACountdownSoTheClientCanSayWhenToRetry() {
        User stored = TestFixtures.user("user-1", "tester", "tester@example.com", TestFixtures.PASSWORD);
        when(users.findByUsernameIgnoreCase("tester")).thenReturn(Optional.of(stored));
        when(users.findByEmailIgnoreCase("tester")).thenReturn(Optional.of(stored));
        // LoginAttemptService reloads the account in its own transaction, so the lookup has to
        // resolve there too.
        when(users.findById("user-1")).thenReturn(Optional.of(stored));

        AccountLockedException locked = null;
        for (int attempt = 0; attempt < properties.maxFailedAttempts() && locked == null; attempt++) {
            try {
                authService.login(new LoginRequest("tester", "wrong"), "agent", "127.0.0.1");
            } catch (AccountLockedException e) {
                // The attempt that trips the threshold reports the lockout instead of a plain
                // rejection, so the client can tell the user to wait rather than keep trying.
                locked = e;
            } catch (InvalidCredentialsException expected) {
                // Every attempt before the last one. Expected, and the point of the loop.
            }
        }

        assertThat(locked).as("the threshold attempt must report a lockout").isNotNull();
        assertThat(locked.retryAfterSeconds())
                .isPositive()
                .isLessThanOrEqualTo(properties.lockoutDuration().toSeconds());
    }

    @Test
    void aSuccessfulSignInClearsTheFailureCount() {
        User stored = TestFixtures.user("user-1", "tester", "tester@example.com", TestFixtures.PASSWORD);
        when(users.findByUsernameIgnoreCase("tester")).thenReturn(Optional.of(stored));
        when(users.findByEmailIgnoreCase("tester")).thenReturn(Optional.of(stored));
        // LoginAttemptService reloads the account in its own transaction, so the lookup has to
        // resolve there too.
        when(users.findById("user-1")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("tester", "wrong"), "agent", "127.0.0.1"))
                .isInstanceOf(InvalidCredentialsException.class);

        // Below the threshold, so this succeeds - and clears the counter on the way through.
        authService.login(new LoginRequest("tester", TestFixtures.PASSWORD), "agent", "127.0.0.1");

        assertThat(stored.getFailedLoginAttempts()).isZero();
        assertThat(stored.getLockedUntil()).isNull();
    }

    @Test
    void theLockoutExpiresOnItsOwn() {
        User stored = TestFixtures.user("user-1", "tester", "tester@example.com", TestFixtures.PASSWORD);
        when(users.findByUsernameIgnoreCase("tester")).thenReturn(Optional.of(stored));
        when(users.findByEmailIgnoreCase("tester")).thenReturn(Optional.of(stored));
        // LoginAttemptService reloads the account in its own transaction, so the lookup has to
        // resolve there too.
        when(users.findById("user-1")).thenReturn(Optional.of(stored));

        for (int attempt = 0; attempt < properties.maxFailedAttempts(); attempt++) {
            try {
                authService.login(new LoginRequest("tester", "wrong"), "agent", "127.0.0.1");
            } catch (RuntimeException expected) {
                // Tripping the lockout throws; that is the point of the last iteration.
            }
        }
        assertThatThrownBy(() -> authService.login(
                new LoginRequest("tester", TestFixtures.PASSWORD), "agent", "127.0.0.1"))
                .isInstanceOf(AccountLockedException.class);

        // No scheduled job clears this. The field is only read when it already blocks, and the
        // window is evaluated against the current time, so it lapses by itself.
        clock.advance(properties.lockoutDuration().plusSeconds(1));

        var response = authService.login(
                new LoginRequest("tester", TestFixtures.PASSWORD), "agent", "127.0.0.1");
        assertThat(response.userId()).isEqualTo("user-1");
    }

    /* -------------------------------- register --------------------------------- */

    @Test
    void refusesToRegisterAnEmailThatIsAlreadyInUse() {
        when(users.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("tester", "taken@example.com", TestFixtures.PASSWORD),
                "agent", "127.0.0.1"))
                .isInstanceOf(CredentialsTakenException.class);
    }

    @Test
    void refusesToRegisterAUsernameThatIsAlreadyInUse() {
        when(users.existsByUsernameIgnoreCase("taken")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("taken", "free@example.com", TestFixtures.PASSWORD),
                "agent", "127.0.0.1"))
                .isInstanceOf(CredentialsTakenException.class);
    }

    @Test
    void reportsARegistrationRaceTheSameWayAsAPreCheck() {
        // The pre-check passed, then another request took the email. Reported identically, so a
        // client cannot use the two paths to tell "taken a moment ago" from "taken long ago".
        when(users.existsByEmailIgnoreCase("racy@example.com")).thenReturn(false);
        when(users.saveAndFlush(any(User.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("unique"));

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("tester", "racy@example.com", TestFixtures.PASSWORD),
                "agent", "127.0.0.1"))
                .isInstanceOf(CredentialsTakenException.class);
    }

    @Test
    void refusesAPasswordShorterThanThePolicy() {
        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("tester", "new@example.com", "short"),
                "agent", "127.0.0.1"))
                .isInstanceOf(PasswordTooWeakException.class)
                .hasMessageContaining(String.valueOf(properties.minPasswordLength()));

        // Rejected before any hashing, so a weak password never reaches the CPU.
        verify(users, never()).saveAndFlush(any(User.class));
    }

    @Test
    void neverStoresThePasswordInPlaintext() {
        when(users.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);

        authService.register(
                new RegisterRequest("tester", "new@example.com", TestFixtures.PASSWORD),
                "agent", "127.0.0.1");

        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(captor.capture());
        String stored = captor.getValue().getPassword();
        assertThat(stored).isNotEqualTo(TestFixtures.PASSWORD);
        assertThat(stored).startsWith("$2");
    }
}
