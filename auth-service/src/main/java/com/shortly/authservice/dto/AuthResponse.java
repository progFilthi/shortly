package com.shortly.authservice.dto;

import java.time.Instant;

/**
 * A successful authentication result.
 *
 * <p>Field names are chosen so the existing iOS {@code AuthSession} keeps decoding unchanged:
 * {@code token} is the access token, exactly as before. The refresh fields are additive, and
 * Swift's {@code Decodable} ignores keys it does not know, so an older client binary is
 * unaffected by their presence.
 *
 * @param token        access token; send as {@code Authorization: Bearer <token>}
 * @param refreshToken single-use; send to /refresh to obtain the next pair
 * @param expiresIn    access token lifetime in seconds, so the client can refresh proactively
 *                     rather than waiting for a 401
 * @param refreshExpiresIn refresh token lifetime in seconds
 */
public record AuthResponse(
        String token,
        String refreshToken,
        long expiresIn,
        long refreshExpiresIn,
        String userId,
        String username,
        String email,
        Instant issuedAt
) {
}
