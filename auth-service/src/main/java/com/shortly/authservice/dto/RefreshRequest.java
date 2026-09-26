package com.shortly.authservice.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A refresh-token exchange.
 * <p>
 * The token travels in the body rather than a cookie so the same endpoint works for a native
 * client, where cookie handling is a platform concern rather than an HTTP one.
 */
public record RefreshRequest(

        @NotBlank(message = "refreshToken is required.")
        String refreshToken
) {
}
