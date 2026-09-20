package com.shortly.authservice.dto;

import lombok.Builder;

@Builder
public record AuthResponse(
        String token,
        String userId,
        String username,
        String email
) {
}
