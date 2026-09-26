package com.shortly.authservice.service;

import com.shortly.authservice.TestFixtures;
import com.shortly.authservice.config.AuthProperties;
import com.shortly.authservice.entity.RefreshToken;
import com.shortly.authservice.exceptions.RefreshTokenInvalidException;
import com.shortly.authservice.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Refresh-token rotation and reuse detection.
 *
 * <p>Rotation is the mechanism that makes a stolen refresh token detectable. The rules under
 * test: a token is consumed on use, a replacement is issued in the same family, a replay inside a
 * short grace window is tolerated (mobile retries), and a replay outside it revokes the whole
 * family (someone else holds a copy).
 */
class RefreshTokenServiceTest {

    private RefreshTokenRepository repository;
    private TestFixtures.MutableClock clock;
    private AuthProperties properties;
    private RefreshTokenService service;

    /** In-memory stand-in, so the rotation logic is exercised without a database. */
    private final Map<String, RefreshToken> byHash = new HashMap<>();

    @BeforeEach
    void setUp() {
        repository = mock(RefreshTokenRepository.class);
        clock = new TestFixtures.MutableClock();
        properties = TestFixtures.properties();
        service = TestFixtures.refreshTokenService(repository, properties, clock);

        byHash.clear();

        when(repository.findByTokenHash(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(byHash.get(invocation.getArgument(0))));

        when(repository.save(any(RefreshToken.class))).thenAnswer(invocation -> {
            RefreshToken saved = invocation.getArgument(0);
            byHash.put(saved.getTokenHash(), saved);
            return saved;
        });

        when(repository.revokeActiveInFamily(any(UUID.class), any()))
                .thenAnswer(invocation -> {
                    UUID family = invocation.getArgument(0);
                    java.time.Instant when = invocation.getArgument(1);
                    int count = 0;
                    for (RefreshToken token : byHash.values()) {
                        if (token.getFamilyId().equals(family)
                                && !token.isConsumed() && !token.isRevoked()) {
                            token.revoke(when);
                            count++;
                        }
                    }
                    return count;
                });

        when(repository.revokeActiveForUser(anyString(), any()))
                .thenAnswer(invocation -> {
                    String userId = invocation.getArgument(0);
                    java.time.Instant when = invocation.getArgument(1);
                    int count = 0;
                    for (RefreshToken token : byHash.values()) {
                        if (token.getUserId().equals(userId)
                                && !token.isConsumed() && !token.isRevoked()) {
                            token.revoke(when);
                            count++;
                        }
                    }
                    return count;
                });
    }

    /* --------------------------------- issuing ---------------------------------- */

    @Test
    void issuesADistinctTokenEveryTime() {
        var first = service.issue("user-1", null, "agent", "127.0.0.1");
        var second = service.issue("user-1", null, "agent", "127.0.0.1");

        assertThat(first.token()).isNotEqualTo(second.token());
    }

    @Test
    void neverStoresTheTokenItHandsOut() {
        var issued = service.issue("user-1", null, "agent", "127.0.0.1");

        // A database leak must not yield working refresh tokens, so the stored value is a hash.
        assertThat(issued.record().getTokenHash()).isNotEqualTo(issued.token());
        assertThat(issued.record().getTokenHash()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void theSameInputAlwaysHashesToTheSameStoredValue() {
        // Hashing has to be deterministic or lookup by hash would never find anything.
        assertThat(RefreshTokenService.hash("abc")).isEqualTo(RefreshTokenService.hash("abc"));
        assertThat(RefreshTokenService.hash("abc")).isNotEqualTo(RefreshTokenService.hash("abd"));
    }

    @Test
    void aNewSignInStartsANewFamily() {
        var first = service.issue("user-1", null, "agent", "127.0.0.1");
        var second = service.issue("user-1", null, "agent", "127.0.0.1");

        assertThat(first.record().getFamilyId()).isNotEqualTo(second.record().getFamilyId());
    }

    /* -------------------------------- rotation ---------------------------------- */

    @Test
    void rotationIssuesANewTokenInTheSameFamily() {
        var original = service.issue("user-1", null, "agent", "127.0.0.1");

        var rotated = service.rotate(original.token(), "agent", "127.0.0.1");

        assertThat(rotated.token()).isNotEqualTo(original.token());
        // Same family, so a later reuse can revoke the whole session.
        assertThat(rotated.record().getFamilyId()).isEqualTo(original.record().getFamilyId());
        assertThat(rotated.record().getUserId()).isEqualTo("user-1");
    }

    @Test
    void rotationConsumesThePresentedToken() {
        var original = service.issue("user-1", null, "agent", "127.0.0.1");

        service.rotate(original.token(), "agent", "127.0.0.1");

        assertThat(original.record().isConsumed()).isTrue();
    }

    @Test
    void anUnknownTokenIsRejectedWithoutRevokingAnything() {
        // A forged token identifies no family, so there is nothing to revoke. Revoking on an
        // unknown token would let an attacker destroy arbitrary sessions by guessing.
        assertThatThrownBy(() -> service.rotate("never-issued", "agent", "127.0.0.1"))
                .isInstanceOf(RefreshTokenInvalidException.class);

        var legitimate = service.issue("user-1", null, "agent", "127.0.0.1");
        assertThat(legitimate.record().isRevoked()).isFalse();
    }

    @Test
    void anExpiredTokenIsRejected() {
        var issued = service.issue("user-1", null, "agent", "127.0.0.1");

        clock.advance(properties.refreshTokenTtl().plusSeconds(1));

        assertThatThrownBy(() -> service.rotate(issued.token(), "agent", "127.0.0.1"))
                .isInstanceOf(RefreshTokenInvalidException.class);
    }

    /* ---------------------------- reuse detection ------------------------------ */

    @Test
    void aReplayInsideTheGraceWindowIsTolerated() {
        // Mobile clients retry after a lost response. Without this, the caller loses the only
        // copy of its token and the user appears logged out at random.
        var original = service.issue("user-1", null, "agent", "127.0.0.1");
        var rotated = service.rotate(original.token(), "agent", "127.0.0.1");

        clock.advance(properties.refreshReuseGrace().minusSeconds(1));

        assertThatThrownBy(() -> service.rotate(original.token(), "agent", "127.0.0.1"))
                .isInstanceOf(RefreshTokenInvalidException.class);

        // The session survives: the replacement is untouched.
        assertThat(rotated.record().isRevoked()).isFalse();
    }

    @Test
    void aReplayOutsideTheGraceWindowRevokesTheWholeFamily() {
        // Two parties now hold the token: the real client and whoever copied it. There is no way
        // to tell which is which, so the only safe move is to end the session for both.
        var original = service.issue("user-1", null, "agent", "127.0.0.1");
        var rotated = service.rotate(original.token(), "agent", "127.0.0.1");

        clock.advance(properties.refreshReuseGrace().plusSeconds(1));

        assertThatThrownBy(() -> service.rotate(original.token(), "agent", "127.0.0.1"))
                .isInstanceOf(RefreshTokenInvalidException.class);

        assertThat(rotated.record().getFamilyId()).isEqualTo(original.record().getFamilyId());
        assertThat(rotated.record().isRevoked())
                .as("the stolen-token holder's replacement must be dead too")
                .isTrue();
    }

    @Test
    void reuseDetectionDoesNotReachIntoOtherSessions() {
        // A family is a single sign-in. Revoking every session on any one replay would let an
        // attacker who can obtain a single token log the user out everywhere.
        var sessionOne = service.issue("user-1", null, "agent", "127.0.0.1");
        var rotatedOne = service.rotate(sessionOne.token(), "agent", "127.0.0.1");
        var sessionTwo = service.issue("user-1", null, "phone", "127.0.0.2");

        clock.advance(properties.refreshReuseGrace().plusSeconds(1));
        try {
            service.rotate(sessionOne.token(), "agent", "127.0.0.1");
        } catch (RefreshTokenInvalidException expected) {
            // The replay we are provoking.
        }

        assertThat(rotatedOne.record().isRevoked()).isTrue();
        assertThat(sessionTwo.record().isRevoked())
                .as("an unrelated sign-in must survive another session's replay")
                .isFalse();
    }

    @Test
    void presentingARevokedTokenRevokesTheFamilyAgain() {
        // Covers the case where a token was revoked by logout and is then replayed.
        var original = service.issue("user-1", null, "agent", "127.0.0.1");
        var rotated = service.rotate(original.token(), "agent", "127.0.0.1");
        service.revokeFamily(original.record().getFamilyId());

        assertThatThrownBy(() -> service.rotate(rotated.token(), "agent", "127.0.0.1"))
                .isInstanceOf(RefreshTokenInvalidException.class);
    }

    /* --------------------------------- logout ----------------------------------- */

    @Test
    void revokingAFamilyLeavesOtherUsersAlone() {
        var mine = service.issue("user-1", null, "agent", "127.0.0.1");
        var theirs = service.issue("user-2", null, "agent", "127.0.0.1");

        service.revokeFamily(mine.record().getFamilyId());

        assertThat(mine.record().isRevoked()).isTrue();
        assertThat(theirs.record().isRevoked()).isFalse();
    }

    @Test
    void revokeAllForUserCoversEveryFamilyButNotOtherUsers() {
        var desktop = service.issue("user-1", null, "desktop", "127.0.0.1");
        var phone = service.issue("user-1", null, "phone", "127.0.0.2");
        var other = service.issue("user-2", null, "other", "127.0.0.3");

        int revoked = service.revokeAllForUser("user-1");

        assertThat(revoked).isEqualTo(2);
        assertThat(desktop.record().isRevoked()).isTrue();
        assertThat(phone.record().isRevoked()).isTrue();
        assertThat(other.record().isRevoked()).isFalse();
    }

    @Test
    void revokingTwiceIsANoOp() {
        // Logout has to be idempotent: a client that lost the response retries, and a second
        // revocation must not be an error or report a different count.
        service.issue("user-1", null, "agent", "127.0.0.1");
        var family = byHash.values().iterator().next().getFamilyId();

        assertThat(service.revokeFamily(family)).isEqualTo(1);
        assertThat(service.revokeFamily(family)).isZero();
    }

    @Test
    void aRevokedTokenIsNotActive() {
        var issued = service.issue("user-1", null, "agent", "127.0.0.1");

        assertThat(issued.record().isActiveAt(clock.instant())).isTrue();
        issued.record().revoke(clock.instant());
        assertThat(issued.record().isActiveAt(clock.instant())).isFalse();
    }
}
