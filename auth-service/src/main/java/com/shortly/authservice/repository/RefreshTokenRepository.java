package com.shortly.authservice.repository;

import com.shortly.authservice.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes every live token in a family.
     * <p>
     * A bulk update rather than a load-and-iterate: reuse detection can hit a family with many
     * rows, and each of those rows would otherwise be a separate round trip inside a request.
     * The {@code consumedAt IS NULL AND revokedAt IS NULL} predicate is what makes repeated calls
     * idempotent, so revoking an already-revoked family touches nothing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :when
             where t.familyId = :familyId
               and t.consumedAt is null
               and t.revokedAt is null
            """)
    int revokeActiveInFamily(@Param("familyId") UUID familyId, @Param("when") Instant when);

    /** Backs "log out everywhere". */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :when
             where t.userId = :userId
               and t.consumedAt is null
               and t.revokedAt is null
            """)
    int revokeActiveForUser(@Param("userId") String userId, @Param("when") Instant when);

    /**
     * Housekeeping. Deletes anything already expired, whether or not it was consumed or revoked -
     * those rows have no remaining use once the expiry has passed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    long countByUserIdAndConsumedAtIsNullAndRevokedAtIsNull(String userId);
}
