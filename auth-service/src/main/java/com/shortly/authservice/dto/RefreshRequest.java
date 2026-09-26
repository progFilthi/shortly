package com.shortly.authservice.dto;

import jakarta.validation.constraints.NotBlank;

/** A refresh-token exchange. */
public record RefreshRequest(

        @NotBlank(message = "refreshToken is required.")
        String refreshToken
) {
}
