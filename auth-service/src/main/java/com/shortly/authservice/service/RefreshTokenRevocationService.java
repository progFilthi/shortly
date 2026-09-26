package com.shortly.authservice.service;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.shortly.authservice.repository.RefreshTokenRepository;

/** Revocation that must survive a rollback. */
@Service
public class RefreshTokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenRevocationService.class);

    private final RefreshTokenRepository repository;

    public RefreshTokenRevocationService(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    /** Revokes every live token in a family, committing independently of the caller. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(UUID familyId, Instant when) {
        int revoked = repository.revokeActiveInFamily(familyId, when);
        log.info("Revoked {} token(s) in family {}", revoked, familyId);
        return revoked;
    }

    /** Revokes every live token for a user across all families - "log out everywhere". */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForUser(String userId, Instant when) {
        int revoked = repository.revokeActiveForUser(userId, when);
        log.info("Revoked {} token(s) for user {}", revoked, userId);
        return revoked;
    }
}
