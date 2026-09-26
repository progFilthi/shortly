package com.shortly.authservice.service;

import com.shortly.authservice.TestFixtures;
import com.shortly.authservice.config.AuthProperties;
import com.shortly.authservice.entity.RefreshToken;
import com.shortly.authservice.exceptions.RefreshTokenInvalidException;
import com.shortly.authservice.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the shape that quietly made reuse detection a no-op.
 *
 * <p>Revoking the family and then throwing rolls the revocation back with the surrounding
 * transaction, so it has to commit independently - which needs {@code REQUIRES_NEW}, which in
 * turn only takes effect through a Spring proxy, which means it cannot be a self-invocation
 * inside {@link RefreshTokenService}.
 *
 * <p>Behaviour is covered in {@link RefreshTokenServiceTest}; this class covers the wiring
 * that behaviour silently depends on.
 *
 * <p><b>What these tests cannot prove.</b> A mocked repository has no transaction, so nothing
 * here can demonstrate that the revocation survives the rollback. Only a real database can,
 * which is why {@code scripts/e2e-auth-test.sh} and the iOS {@code LiveBackendTests} assert it
 * end to end. What is asserted here is that the structure they rely on stays put, so the
 * failure cannot return quietly.
 */
class RefreshTokenRevocationTest {

    private RefreshTokenRepository repository;
    private RefreshTokenService service;
    private TestFixtures.MutableClock clock;
    private AuthProperties properties;

    @BeforeEach
    void setUp() {
        repository = mock(RefreshTokenRepository.class);
        clock = new TestFixtures.MutableClock();
        properties = TestFixtures.properties();
        service = new RefreshTokenService(
                repository, new RefreshTokenRevocationService(repository), properties, clock);
    }

    @Test
    void revocationCommitsInItsOwnTransaction() throws Exception {
        Method revokeFamily = RefreshTokenRevocationService.class
                .getMethod("revokeFamily", UUID.class, Instant.class);

        Transactional annotation = revokeFamily.getAnnotation(Transactional.class);

        assertThat(annotation)
                .as("revokeFamily must commit independently of the transaction that throws")
                .isNotNull();
        assertThat(annotation.propagation())
                .as("REQUIRES_NEW is the only propagation that survives the caller's rollback")
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    /**
     * The original defect, as one assertion. {@code REQUIRES_NEW} sat on a method of
     * {@code RefreshTokenService} that called itself, so the proxy was bypassed, the
     * annotation meant nothing, and the update joined the transaction that then rolled it
     * back. Reintroducing that shape is the regression worth catching.
     */
    @Test
    void rotationServiceDoesNotDeclareItsOwnRequiresNewRevocation() {
        boolean declaresRequiresNew = Arrays
                .stream(RefreshTokenService.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Transactional.class))
                .filter(Objects::nonNull)
                .anyMatch(annotation -> annotation.propagation() == Propagation.REQUIRES_NEW);

        assertThat(declaresRequiresNew)
                .as("A REQUIRES_NEW method on RefreshTokenService is inert while nothing "
                        + "self-invokes it, and misleading the moment something does. Keep the "
                        + "work in RefreshTokenRevocationService.")
                .isFalse();
    }

    @Test
    void reuseDetectionRevokesThroughTheCommittingBean() {
        RefreshToken consumed = consumedToken();
        clock.advance(properties.refreshReuseGrace().plusSeconds(1));

        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(consumed));

        assertThatThrownBy(() -> service.rotate("a-token", "agent", "127.0.0.1"))
                .isInstanceOf(RefreshTokenInvalidException.class);

        // Routed through RefreshTokenRevocationService, which is the only place a
        // REQUIRES_NEW revocation can actually take effect.
        verify(repository).revokeActiveInFamily(any(UUID.class), any());
    }

    @Test
    void aReplayInsideTheGraceWindowDoesNotRevokeAnything() {
        RefreshToken consumed = consumedToken();
        clock.advance(properties.refreshReuseGrace().minusSeconds(2));

        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(consumed));

        assertThatThrownBy(() -> service.rotate("a-token", "agent", "127.0.0.1"))
                .isInstanceOf(RefreshTokenInvalidException.class);

        // A mobile retry must not cost the user their session.
        verify(repository, org.mockito.Mockito.never()).revokeActiveInFamily(any(UUID.class), any());
    }

    /** A token consumed at the current instant, so the caller controls how far "now" has moved. */
    private RefreshToken consumedToken() {
        RefreshToken token = new RefreshToken();
        token.setId(UUID.randomUUID());
        token.setUserId("user-1");
        token.setFamilyId(UUID.randomUUID());
        token.setTokenHash("hash");
        token.setIssuedAt(clock.instant().minusSeconds(60));
        token.setExpiresAt(clock.instant().plusSeconds(3600));
        token.consume(clock.instant());
        return token;
    }
}
