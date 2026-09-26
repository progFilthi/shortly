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

    /** Revokes every live token in a family. A bulk update rather than a load-and-iterate: reuse
     * round trip inside a request. */
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

    /** Housekeeping. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    long countByUserIdAndConsumedAtIsNullAndRevokedAtIsNull(String userId);
}
