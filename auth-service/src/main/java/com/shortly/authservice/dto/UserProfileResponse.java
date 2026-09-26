package com.shortly.authservice.dto;

import java.time.Instant;
import java.util.UUID;

/** The signed-in user's own profile. Separate from {@link AuthResponse} so the client can refresh a
 * token issuance. */
public record UserProfileResponse(
        String userId,
        String username,
        String email,
        String profilePictureUrl,
        String bio,
        Instant createdAt,
        long activeSessionCount
) {
}
