package com.shortly.authservice.service;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.shortly.authservice.repository.RefreshTokenRepository;

/**
 * Revocation that must survive a rollback.
 *
 * <p>Exists as its own bean for one reason: {@code @Transactional} works through a proxy, and
 * a self-invocation inside {@link RefreshTokenService} would bypass it. Reuse detection
 * revokes the family and then throws, so if the revocation joined that transaction it would
 * be rolled back with it, leaving exactly the token we just decided to distrust still
 * working. The failure is silent, and the shell-level end-to-end test did not catch it - a
 * mocked repository cannot, because it has no transaction to lose.
 *
 * <p>Kept separate from {@code LoginAttemptService}, which has the same shape for the same
 * reason, so the two independent-commit paths are easy to find together.
 */
@Service
public class RefreshTokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenRevocationService.class);

    private final RefreshTokenRepository repository;

    public RefreshTokenRevocationService(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    /**
     * Revokes every live token in a family, committing independently of the caller.
     *
     * @return how many tokens were revoked
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(UUID familyId, Instant when) {
        int revoked = repository.revokeActiveInFamily(familyId, when);
        log.info("Revoked {} token(s) in family {}", revoked, familyId);
        return revoked;
    }

    /**
     * Revokes every live token for a user across all families - "log out everywhere".
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForUser(String userId, Instant when) {
        int revoked = repository.revokeActiveForUser(userId, when);
        log.info("Revoked {} token(s) for user {}", revoked, userId);
        return revoked;
    }
}
