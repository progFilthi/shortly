package com.shortly.authservice.dto;

import java.time.Instant;

/** A successful authentication result. */
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
