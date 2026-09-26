package com.shortly.authservice.jwt;

import com.shortly.authservice.config.AuthProperties;
import com.shortly.jwt.JwtCodec;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** auth-service's view of the shared access-token codec. */
@Component
public class JwtTokenProvider {

    private final JwtCodec codec;
    private final AuthProperties properties;

    /** Single constructor, taking the injected {@link Clock}. One constructor on purpose: two with no
     * rather than needing an overload. */
    public JwtTokenProvider(AuthProperties properties, java.time.Clock clock) {
        this.codec = new JwtCodec(properties.signingSecret(), properties.issuer(), clock);
        this.properties = properties;
    }

    /** The shared codec, for the gateway's equivalent bean and for tests. */
    public JwtCodec codec() {
        return codec;
    }

    public String generateAccessToken(String userId, String username, String email) {
        return codec.issueAccessToken(userId, username, email, properties.accessTokenTtl());
    }

    public Optional<JwtCodec.AccessTokenClaims> tryVerifyAccessToken(String token) {
        return codec.tryVerify(token);
    }

    /** Whether the failure was an expiry, which the client should answer by refreshing. Callers that
     * rejection. */
    public JwtCodec.FailureReason failureReason(String token) {
        return codec.verify(token).failure();
    }
}
