package com.shortly.authservice.mapper;

import com.shortly.authservice.dto.AuthResponse;
import com.shortly.authservice.dto.UserProfileResponse;
import com.shortly.authservice.entity.User;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Entity to response mapping. Kept out of the service so services hold no presentation logic. */
@Component
public class UserMapper {

    /**
     * @param token    access token
     * @param refresh  refresh token, returned once and never recoverable again
     * @param issuedAt when this pair was minted, so a client can reason about clock skew
     */
    public AuthResponse toAuthResponse(User user,
                                      String token,
                                      String refresh,
                                      long expiresIn,
                                      long refreshExpiresIn,
                                      Instant issuedAt) {
        if (user == null) {
            return null;
        }
        return new AuthResponse(
                token,
                refresh,
                expiresIn,
                refreshExpiresIn,
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                issuedAt);
    }

    public UserProfileResponse toProfileResponse(User user, long activeSessionCount) {
        return new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getProfilePictureUrl(),
                user.getBio(),
                user.getCreatedAt(),
                activeSessionCount);
    }
}
