package com.shortly.authservice.service;

import com.shortly.authservice.config.AuthProperties;
import com.shortly.authservice.entity.User;
import com.shortly.authservice.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Records sign-in attempts.
 *
 * <p>A separate bean for one reason: the failure counter must survive the exception that
 * accompanies it.
 *
 * <p>{@code AuthService#login} runs in a transaction and throws {@code InvalidCredentials} or
 * {@code AccountLocked} on failure. Rolling that transaction back also rolls back the counter
 * increment, so the count never climbs and the lockout never fires - brute-force protection
 * that looks implemented and is not. Recording the attempt in {@code REQUIRES_NEW} commits it
 * independently of the failure the caller is about to see.
 *
 * <p>A separate class rather than a private method on {@code AuthService}, because Spring's
 * transaction proxy is not applied to self-invocation: a {@code REQUIRES_NEW} method called from
 * inside its own class would quietly run in the caller's transaction.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final UserRepository userRepository;
    private final AuthProperties properties;
    private final Clock clock;

    public LoginAttemptService(UserRepository userRepository,
                               AuthProperties properties,
                               Clock clock) {
        this.userRepository = userRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Increments the failure count and locks the account once it reaches the threshold.
     *
     * @return true if this attempt tripped the lockout
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(String userId) {
        // Reloaded in this transaction rather than reusing the caller's managed instance, whose
        // state the outer rollback is about to discard.
        return userRepository.findById(userId).map(user -> {
            Instant now = clock.instant();
            user.recordFailedLogin(properties.maxFailedAttempts(), properties.lockoutDuration(), now);
            userRepository.saveAndFlush(user);

            boolean locked = user.isLockedAt(now);
            if (locked) {
                log.warn("Account {} locked after {} consecutive failed sign-ins",
                        user.getUsername(), user.getFailedLoginAttempts());
            }
            return locked;
        }).orElse(false);
    }

    /**
     * Clears the counter after a successful sign-in.
     * <p>
     * Also {@code REQUIRES_NEW}, for symmetry and for the same reason: the caller's transaction
     * must not be the thing that decides whether a lockout sticks.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.recordSuccessfulLogin();
            userRepository.saveAndFlush(user);
        });
    }

    /** Seconds remaining on an active lockout, or zero if the account is usable. */
    @Transactional(readOnly = true)
    public long remainingLockoutSeconds(String userId) {
        return userRepository.findById(userId)
                .filter(user -> user.isLockedAt(clock.instant()))
                .map(user -> Math.max(1,
                        java.time.Duration.between(clock.instant(), user.getLockedUntil()).toSeconds()))
                .orElse(0L);
    }
}
