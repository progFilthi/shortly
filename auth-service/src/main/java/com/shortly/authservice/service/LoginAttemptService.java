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

/** Records sign-in attempts. A separate class rather than a private method on {@code AuthService},
 * method called from inside its own class would quietly run in the caller's transaction. */
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

    /** Increments the failure count and locks the account once it reaches the threshold. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(String userId) {
        // Reloaded in this transaction rather than reusing the caller's managed instance, whose state the
        // outer rollback is about to discard.
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

    /** Clears the counter after a successful sign-in. Also {@code REQUIRES_NEW}, for symmetry and for
     * sticks. */
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
